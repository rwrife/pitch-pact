import SwiftUI
import Darwin
import PitchPactStore
@preconcurrency import PitchPactShared

@MainActor struct MatchDayView: View {
    let fixtureId: String
    var homeTeam = "Home"
    var awayTeam = "Away"
    @State private var json = ""
    @State private var side = "HOME"
    @State private var kind = "OPEN_PLAY"
    @State private var jersey = "9"
    @State private var assist = ""
    @State private var other = "10"
    @State private var error = ""
    @State private var endpoint = ""
    @State private var capability = ""
    @State private var syncing = false
    @State private var correctionTarget = ""
    private var enabledExtras: Set<String> {
        guard let bytes = json.data(using: .utf8),
              let snapshot = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any],
              let extras = snapshot["enabledExtras"] as? [String] else { return [] }
        return Set(extras)
    }
    @State private var recoveredElapsed = ""
    private let rules = MatchDayFacade()
    private var boot: String {
        var value = timeval()
        var size = MemoryLayout<timeval>.size
        guard sysctlbyname("kern.boottime", &value, &size, nil, 0) == 0 else { return "unavailable-\(UUID().uuidString)" }
        return "\(value.tv_sec)-\(value.tv_usec)"
    }
    private var uptime: Int64 {
        var timebase = mach_timebase_info_data_t()
        mach_timebase_info(&timebase)
        return Int64(Double(mach_continuous_time()) * Double(timebase.numer) / Double(timebase.denom) / 1_000_000)
    }
    private var epoch: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
    private let kinds = ["OPEN_PLAY", "PENALTY", "OWN_GOAL", "SET_PIECE", "PENALTY_MISS", "SUBSTITUTION", "YELLOW", "RED", "CORNER", "OFFSIDE", "SHOT_ON_TARGET", "SAVE", "INJURY_STOPPAGE"]
    var body: some View {
        Form {
            if !json.isEmpty {
                Section("Score") {
                    Text("OFFICIAL \((try? rules.official(json: json)) ?? "Unavailable")").accessibilityIdentifier("officialScore")
                    Text("Opposing acceptance arrives in M7")
                    Text("Pending score \((try? rules.pending(json: json)) ?? "Unavailable")").accessibilityIdentifier("pendingScore")
                    TimelineView(.periodic(from: .now, by: 1)) { _ in
                        Text((try? clockText()) ?? "Clock requires recovery")
                    }
                    Button("Advance clock") { change { try rules.advance(json: json, uptime: uptime, epoch: epoch, boot: boot, stoppage: false, id: UUID().uuidString, side: side) } }.frame(minHeight: 44)
                    TextField("Confirmed elapsed seconds", text: $recoveredElapsed).keyboardType(.numberPad)
                    Button("Confirm clock recovery") {
                        guard let seconds = Int64(recoveredElapsed), seconds >= 0, seconds <= Int64.max / 1000 else { error = "Enter valid elapsed seconds"; return }
                        change { try rules.recoverClock(json: json, uptime: uptime, epoch: epoch, boot: boot, elapsed: seconds * 1000) }
                    }.frame(minHeight: 44)
                    Button("Start stoppage") { change { try rules.advance(json: json, uptime: uptime, epoch: epoch, boot: boot, stoppage: true, id: UUID().uuidString, side: side) } }.frame(minHeight: 44)
                }
                Section("Capture") {
                    Picker("Side", selection: $side) { Text("Home").tag("HOME"); Text("Away").tag("AWAY") }
                    Picker("Event", selection: $kind) { ForEach(kinds, id: \.self) { Text($0).tag($0) } }
                    TextField("Jersey", text: $jersey).keyboardType(.numberPad)
                    TextField("Optional assist", text: $assist).keyboardType(.numberPad)
                    TextField("Substitute on jersey", text: $other).keyboardType(.numberPad)
                    TextField("Correction target event ID (optional)", text: $correctionTarget)
                    Button("Capture event") {
                        change {
                            let captureJSON = try correctionTarget.isEmpty ? json : rules.initial(id: fixtureId)
                            let captured = try rules.captureInput(json: captureJSON, id: UUID().uuidString, side: side, epoch: epoch, kind: kind, jersey: jersey, assist: assist, other: other)
                            return try correctionTarget.isEmpty ? captured : rules.correct(json: json, target: correctionTarget, replacementJSON: captured, id: UUID().uuidString, epoch: epoch)
                        }
                    }.frame(minHeight: 44)
                    Button("Undo uncommitted tail") { change { try rules.undo(json: json) } }.frame(minHeight: 44)
                }
                Section("Extras") {
                    ForEach(["CORNER", "OFFSIDE", "SHOT_ON_TARGET", "SAVE", "INJURY_STOPPAGE"], id: \.self) { extra in
                        Toggle(extra, isOn: Binding(get: { enabledExtras.contains(extra) }, set: { enabled in
                            var next = enabledExtras
                            if enabled { next.insert(extra) } else { next.remove(extra) }
                            change { try rules.extras(json: json, enabled: next.sorted().joined(separator: ",")) }
                        }))
                    }
                }
                Section("Sync") {
                    TextField("Server endpoint (HTTPS)", text: $endpoint).textInputAutocapitalization(.never)
                    SecureField("Match capability", text: $capability)
                    Button("Sync queued events") { Task { await sync() } }.disabled(syncing).frame(minHeight: 44)
                }
                BroadcastControls(matchId: fixtureId, home: homeTeam, away: awayTeam, endpoint: endpoint, capability: capability, snapshot: {
                    let prepared = try rules.prepareSync(json: json)
                    try AppTeamStore.shared.saveMatchDay(id: fixtureId, sharedJSON: prepared)
                    json = prepared
                    return prepared
                }, sample: { (uptime, epoch, boot) })
                Section("Audit ledger") { Text((try? rules.audit(json: json)) ?? "Ledger requires recovery").font(.caption).textSelection(.enabled) }
            }
            Text(error)
        }.navigationTitle("Match day").onAppear {
            do { json = try AppTeamStore.shared.matchDay(id: fixtureId) ?? rules.initial(id: fixtureId) }
            catch { self.error = error.localizedDescription }
        }
    }
    private func clockText() throws -> String { try rules.clock(json: json, uptime: uptime, epoch: epoch, boot: boot) }
    private func change(_ action: () throws -> String) {
        do { let next = try action(); try AppTeamStore.shared.saveMatchDay(id: fixtureId, sharedJSON: next); json = next; error = "" }
        catch { self.error = error.localizedDescription }
    }
    private func sync() async {
        syncing = true
        defer { syncing = false }
        do {
            guard let base = URL(string: endpoint), base.scheme == "https", base.user == nil, !capability.isEmpty else {
                error = "Enter an HTTPS endpoint and match capability"; return
            }
            let prepared = try rules.prepareSync(json: json)
            try AppTeamStore.shared.saveMatchDay(id: fixtureId, sharedJSON: prepared)
            json = prepared
            let body = try rules.request(json: json)
            let url = base.appendingPathComponent("v0/matches/\(fixtureId)/events")
            var request = URLRequest(url: url, timeoutInterval: 15)
            request.httpMethod = "POST"; request.httpBody = Data(body.utf8)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.setValue("Bearer \(capability)", forHTTPHeaderField: "Authorization")
            let (data, response) = try await URLSession.shared.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200, let receipt = String(data: data, encoding: .utf8) else {
                error = "Sync failed; queue retained"; return
            }
            let next = try rules.receipt(json: json, requestJSON: body, receiptJSON: receipt)
            try AppTeamStore.shared.saveMatchDay(id: fixtureId, sharedJSON: next)
            json = next; error = ""
        } catch { self.error = "Sync failed; queue retained: \(error.localizedDescription)" }
    }
}
