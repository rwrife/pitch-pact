import SwiftUI
import Darwin
import PitchPactStore
@preconcurrency import PitchPactShared

@MainActor struct MatchDayView: View {
    let fixtureId: String
    var homeTeam = "Home"
    var awayTeam = "Away"
    var fixtureHomeTeamId: String? = nil
    var fixtureAwayTeamId: String? = nil
    var coordinatedLinkId: String? = nil
    var official: String? = nil
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
    @State private var linkedParties: (link: TeamLink, creds: Credentials)?
    @State private var outbox = ""
    @State private var outboxFailed = false
    private let consensusRules = ConsensusFacade()
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
                    let labels = (try? rules.scoreLabels(json: json, coordinated: coordinatedLinkId != nil, official: official))
                        ?? ["Score unavailable", "Local captures unavailable"]
                    Text(labels[0]).accessibilityIdentifier("officialScore")
                    if coordinatedLinkId != nil {
                        Text("Linked score changes require opposing review. Local captures stay on device until queued for linked submission below.")
                    }
                    Text(labels[1]).accessibilityIdentifier("pendingScore")
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
                if coordinatedLinkId != nil { Section("Linked consensus submission") {
                    Text("Every linked goal stays in the local ledger and queues durably. It becomes official only after the opposing sideline accepts.").font(.caption)
                    if outboxFailed { Text("Durable outbox unavailable. Queue stopped to prevent data loss.").foregroundStyle(.red) }
                    else if let parties = linkedParties {
                        if !outbox.isEmpty {
                            Text("Consensus outbox: \((try? consensusRules.outboxSummary(outboxJson: outbox)) ?? "unavailable")")
                                .accessibilityIdentifier("match.outbox")
                        }
                        let rows = (try? consensusRules.capturedGoalRows(stateJson: json, fixtureHomeTeamId: fixtureHomeTeamId ?? "",
                            fixtureAwayTeamId: fixtureAwayTeamId ?? "", myTeamId: parties.creds.team.id, outboxJson: outbox)) ?? []
                        if rows.isEmpty { Text("No unsent own goals ready for opposing review").accessibilityIdentifier("match.noPendingGoals") }
                        ForEach(rows, id: \.self) { row in
                            let parts = row.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
                            if parts.count == 2 {
                                HStack {
                                    Text(parts[1])
                                    Button("Submit for opponent review") {
                                        enqueueGoal(eventId: parts[0], parties: parties)
                                    }.accessibilityIdentifier("match.submitGoal.\(parts[0])")
                                }
                            }
                        }
                        Button("Send queued consensus changes") { Task { await sendLinked() } }.frame(minHeight: 44)
                    } else {
                        Text("Linked scorer credentials unavailable. Open Team registry, refresh links for this fixture's team, then return. Local capture is retained.")
                            .accessibilityIdentifier("match.linkedUnavailable")
                    }
                } }
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
            loadLinked()
        }
    }
    private func loadLinked() {
        guard let linkId = coordinatedLinkId, let homeId = fixtureHomeTeamId, let awayId = fixtureAwayTeamId,
              let data = UserDefaults.standard.data(forKey: "registry.link.\(linkId)"),
              let link = try? JSONDecoder().decode(TeamLink.self, from: data), link.state == "active",
              Set([link.firstTeamId, link.secondTeamId]) == Set([homeId, awayId]) else { linkedParties = nil; return }
        var matches: [(TeamLink, Credentials)] = []
        for teamId in [homeId, awayId] where Set([link.firstTeamId, link.secondTeamId]).contains(teamId) {
            if let creds = try? RegistrySecrets.load(teamId) { matches.append((link, creds)) }
        }
        guard matches.count == 1 else { linkedParties = nil; return }
        let parties = matches[0]
        linkedParties = parties
        endpoint = UserDefaults.standard.string(forKey: "registry.endpoint") ?? endpoint
        do {
            let saved = try ConsensusOutboxStore.load(endpoint, link.id, parties.1.team.id) ?? consensusRules.outboxNew()
            _ = try consensusRules.outboxSummary(outboxJson: saved) // Refuse corrupt files; never overwrite queued writes.
            outbox = saved
            outboxFailed = false
        } catch {
            outboxFailed = true
            self.error = "Consensus outbox unavailable; local capture retained. Repair storage before sending."
        }
    }
    private func enqueueGoal(eventId: String, parties: (link: TeamLink, creds: Credentials)) {
        guard !eventId.isEmpty, Set([parties.link.firstTeamId, parties.link.secondTeamId]) == Set([fixtureHomeTeamId, fixtureAwayTeamId].compactMap { $0 }) else {
            error = "Linked goal submission requires complete fixture and active link details"; return
        }
        do {
            let next = try consensusRules.enqueueCapturedGoal(outboxJson: outbox, stateJson: json, eventId: eventId,
                linkId: parties.link.id, fixtureHomeTeamId: fixtureHomeTeamId ?? "", fixtureAwayTeamId: fixtureAwayTeamId ?? "",
                linkFirstTeamId: parties.link.firstTeamId, linkSecondTeamId: parties.link.secondTeamId, myTeamId: parties.creds.team.id)
            try ConsensusOutboxStore.save(endpoint, parties.link.id, parties.creds.team.id, next)
            outbox = next; error = ""
        } catch { self.error = error.localizedDescription }
    }
    private func sendLinked() async {
        guard let parties = linkedParties, !endpoint.isEmpty else { error = "Registry endpoint and active link required"; return }
        let transport = RegistryTransport(endpoint: endpoint, key: parties.creds.ownerKey)
        do {
            while true {
                let entryJson = try consensusRules.outboxHead(outboxJson: outbox)
                if entryJson.isEmpty { break }
                let path = try consensusRules.outboxPath(entryJson: entryJson)
                let body = try consensusRules.outboxBody(entryJson: entryJson)
                let id = try consensusRules.outboxId(entryJson: entryJson)
                let status = await transport.statusOf("POST", path, body: body)
                let next: String
                if (200...299).contains(status) { next = try consensusRules.outboxDelivered(outboxJson: outbox, id: id) }
                else if (400...499).contains(status) { next = try consensusRules.outboxRefuse(outboxJson: outbox, id: id, status: Int32(status)) }
                else { break }
                try ConsensusOutboxStore.save(endpoint, parties.link.id, parties.creds.team.id, next)
                outbox = next
            }
            error = try consensusRules.outboxSummary(outboxJson: outbox)
        } catch { self.error = error.localizedDescription }
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
