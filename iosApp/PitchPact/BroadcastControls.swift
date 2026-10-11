import SwiftUI
import CoreImage.CIFilterBuiltins
import Security
@preconcurrency import PitchPactShared

/// Redirects are refused outright: a manager-supplied server must never move the capability to another origin.
private final class NoRedirectDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
private let noRedirectDelegate = NoRedirectDelegate()

private struct BroadcastSession: Codable {
    var code: String
    var key: String
    var endpoint: String
    var seq: Int64 = 0
    var pendingBody: String?
}

/// Capability and retry checkpoint live in device-only Keychain, never a share URL or backup.
private enum BroadcastSecrets {
    static func load(_ match: String) throws -> Data? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.infinityball.pitchpact.broadcast", kSecAttrAccount as String: match,
            kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw CocoaError(.fileReadUnknown) }
        return result as? Data
    }
    static func save(_ match: String, _ data: Data?) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.infinityball.pitchpact.broadcast", kSecAttrAccount as String: match]
        if let data {
            let values = [kSecValueData as String: data]
            let status = SecItemUpdate(query as CFDictionary, values as CFDictionary)
            if status == errSecItemNotFound {
                var item = query
                item[kSecValueData as String] = data
                item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
                guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { throw CocoaError(.fileWriteUnknown) }
            } else if status != errSecSuccess { throw CocoaError(.fileWriteUnknown) }
        } else {
            let status = SecItemDelete(query as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else { throw CocoaError(.fileWriteUnknown) }
        }
    }
}

/// Networking only; projection and score semantics stay in shared BroadcastFacade.
@MainActor struct BroadcastControls: View {
    let matchId: String
    let home: String
    let away: String
    let endpoint: String
    let capability: String
    let snapshot: @MainActor @Sendable () throws -> String
    let sample: @MainActor @Sendable () -> (Int64, Int64, String)
    @State private var session: BroadcastSession?
    @State private var busy = false
    @State private var count = 0
    @State private var message = ""
    private let rules = BroadcastFacade()

    var body: some View {
        Section("Broadcast · jersey numbers only") {
            Button("Start broadcast") { Task { await start() } }
                .disabled(session != nil || busy || capability.isEmpty || endpoint.isEmpty)
                .accessibilityIdentifier("broadcast.start")
            if let current = session {
                let link = "\(current.endpoint.trimmingCharacters(in: CharacterSet(charactersIn: "/")))/spectate/\(current.code)"
                Text("Code: \(current.code)").font(.title2).textSelection(.enabled)
                Text("\(count) recent viewers (approximate)")
                if let image = qr(link) {
                    Image(uiImage: image).interpolation(.none).resizable().scaledToFit().frame(width: 180, height: 180)
                        .accessibilityLabel("QR code for spectator link")
                }
                ShareLink("Share spectator link", item: link)
                Button("Copy short link") { UIPasteboard.general.string = link }
                Button("Stop broadcast", role: .destructive) { Task { await stop() } }.disabled(busy)
            }
            Text(message).foregroundStyle(.secondary)
            Text("Only accepted changes are official. Opposing-side review is a later milestone. Keep this screen open to publish; offline retries retain every captured event.").font(.caption)
        }
        .onAppear {
            do {
                if let data = try BroadcastSecrets.load(matchId) { session = try JSONDecoder().decode(BroadcastSession.self, from: data) }
            } catch { message = "Broadcast recovery failed; saved credentials were not replaced." }
        }
        .task(id: session?.code) {
            guard session != nil else { return }
            while !Task.isCancelled && session != nil {
                await publish()
                do { try await Task.sleep(for: .seconds(10)) } catch { return }
            }
        }
    }
    private func qr(_ link: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(link.utf8)
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
              let image = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: image)
    }
    private func persist(_ value: BroadcastSession?) throws {
        try BroadcastSecrets.save(matchId, value.map { try JSONEncoder().encode($0) })
        session = value
    }
    private func request(_ base: String, _ path: String, _ method: String, _ key: String?, _ body: String? = nil) async throws -> (Data, HTTPURLResponse) {
        guard let url = URL(string: base), url.scheme == "https", url.host != nil, url.user == nil, url.password == nil,
              url.query == nil, url.fragment == nil else { throw URLError(.badURL) }
        var request = URLRequest(url: url.appendingPathComponent(path), timeoutInterval: 15)
        request.httpMethod = method
        request.httpBody = body.map { Data($0.utf8) }
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let key { request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization") }
        let (data, response) = try await URLSession.shared.data(for: request, delegate: noRedirectDelegate)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw URLError(.badServerResponse) }
        return (data, http)
    }
    private func start() async {
        busy = true
        defer { busy = false }
        do {
            let body = try rules.create(matchId: matchId, home: home, away: away, id: UUID().uuidString)
            let (data, _) = try await request(endpoint, "v0/broadcasts", "POST", capability, body)
            struct Started: Decodable { let spectatorCode: String; let broadcastSessionKey: String }
            let response = try JSONDecoder().decode(Started.self, from: data)
            try persist(BroadcastSession(code: response.spectatorCode, key: response.broadcastSessionKey, endpoint: endpoint))
            message = "Started"
        } catch { message = "Could not start broadcast. Check endpoint, capability and connectivity." }
    }
    private func publish() async {
        guard !busy, var current = session else { return }
        busy = true
        defer { busy = false }
        do {
            if current.pendingBody == nil {
                let (uptime, epoch, boot) = sample()
                current.pendingBody = try rules.update(json: snapshot(), seq: current.seq + 1, id: UUID().uuidString, uptime: uptime, epoch: epoch, boot: boot)
                try persist(current)
            }
            let (ack, _) = try await request(current.endpoint, "v0/broadcasts/\(current.code)", "PATCH", current.key, current.pendingBody)
            struct Ack: Decodable { let ackSeq: Int64 }
            let response = try JSONDecoder().decode(Ack.self, from: ack)
            guard response.ackSeq == current.seq + 1 else { throw URLError(.cannotParseResponse) }
            current.seq = response.ackSeq; current.pendingBody = nil
            try persist(current)
            let (data, _) = try await request(current.endpoint, "v0/spectate/\(current.code)", "GET", nil)
            struct Viewers: Decodable { let spectatorCount: Int }
            count = try JSONDecoder().decode(Viewers.self, from: data).spectatorCount
            message = "Live"
        } catch { message = "Publish pending. The saved request will retry without duplicate events." }
    }
    private func stop() async {
        guard let current = session else { return }
        busy = true
        defer { busy = false }
        do {
            _ = try await request(current.endpoint, "v0/broadcasts/\(current.code)", "DELETE", current.key)
            try persist(nil)
            message = "Stopped"
        } catch { message = "Stop not confirmed. Retaining the session for retry." }
    }
}
