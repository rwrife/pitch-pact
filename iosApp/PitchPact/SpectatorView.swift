import SwiftUI
@preconcurrency import PitchPactShared

@MainActor struct SpectatorView: View {
    @State private var endpoint = "https://pitchpact.infinityball.com"
    @State private var code = ""
    @State private var watching = false
    @State private var summary = ""
    @State private var events = ""
    @State private var message = ""
    private let rules = BroadcastFacade()

    var body: some View {
        Form {
            Section("Join live game") {
                TextField("Server endpoint (HTTPS)", text: $endpoint).textInputAutocapitalization(.never)
                TextField("6-character broadcast code", text: $code)
                    .textInputAutocapitalization(.characters)
                    .onChange(of: code) { _, new in code = String(new.prefix(6)).uppercased() }
                Toggle("Watch match", isOn: $watching)
                    .disabled(code.count != 6 || endpoint.isEmpty)
            }
            if !message.isEmpty {
                Section { Text(message).foregroundStyle(.secondary) }
            }
            if !summary.isEmpty {
                Section("Scoreboard") {
                    Text(summary)
                        .font(.headline)
                        .accessibilityIdentifier("spectatorSummary")
                }
                Section("Live play by play") {
                    Text(events)
                        .font(.footnote)
                        .textSelection(.enabled)
                }
            }
        }
        .navigationTitle("Spectator")
        .task(id: watching) {
            guard watching else { return }
            while watching {
                await poll()
                try? await Task.sleep(nanoseconds: 10_000_000_000)
            }
        }
    }

    private func poll() async {
        guard let base = URL(string: endpoint), base.scheme == "https", base.user == nil else {
            message = "Enter a valid HTTPS endpoint"
            watching = false
            return
        }
        let url = base.appendingPathComponent("v0/spectate/\(code)")
        var request = URLRequest(url: url, timeoutInterval: 10)
        request.httpMethod = "GET"
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200,
                  let text = String(data: data, encoding: .utf8) else {
                message = "Game not found or finished"
                return
            }
            summary = try rules.summary(json: text)
            events = try rules.events(json: text)
            message = "Live"
        } catch {
            message = "Spectator poll error: \(error.localizedDescription)"
        }
    }
}
