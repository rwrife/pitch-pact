import Foundation
import CryptoKit
import Security
import SwiftUI
import PitchPactStore
@preconcurrency import PitchPactShared

// Native transport structs follow shared/src/commonMain/.../dto/Registry.kt byte-for-byte.
// Registry business rules and the schedule merge live in the shared KMP module.
private struct Listing: Codable { let clubName: String; let region: String; let ageGroup: String }
private struct ListedTeam: Codable { let id: String; let listing: Listing; let listed: Bool }
private struct Credentials: Codable { var team: ListedTeam; let ownerKey: String; var teamCode: String }
private struct LinkRequest: Codable { let id: String; let requesterTeamId: String; let recipientTeamId: String; let state: String; let expiresAt: Int64 }
private struct TeamLink: Codable { let id: String; let firstTeamId: String; let secondTeamId: String; let state: String }
private struct SharedFixture: Codable, Identifiable {
    let id: String; let linkId: String; let homeTeamId: String; let awayTeamId: String
    let startEpochMillis: Int64; let location: String; let homeUniform: String; let awayUniform: String
    let state: String; let confirmedBy: Set<String>; let revision: Int
}
private struct TeamCode: Codable { let teamCode: String }
private struct Visibility: Codable { let listed: Bool }
private struct Create: Codable { let listing: Listing; let idempotencyKey: String }
private struct RequestLink: Codable { let requesterTeamId: String; let idempotencyKey: String }
private struct Propose: Codable {
    let startEpochMillis: Int64; let location: String; let homeUniform: String; let awayUniform: String; let idempotencyKey: String
}
private struct ScheduleProjection: Codable {
    let locations: [GameLocation]; let fixtures: [PitchPactStore.Fixture]; let removedFixtureIds: [String]
}

/// Device-only Keychain: team owner secret must not join share sheets, URL logs or cloud backups.
private enum RegistrySecrets {
    static func load(_ localTeamId: String) throws -> Credentials? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.infinityball.pitchpact.registry", kSecAttrAccount as String: localTeamId,
            kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw CocoaError(.fileReadUnknown) }
        guard let data = result as? Data else { return nil }
        return try JSONDecoder().decode(Credentials.self, from: data)
    }
    static func save(_ localTeamId: String, _ creds: Credentials) throws {
        let data = try JSONEncoder().encode(creds)
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.infinityball.pitchpact.registry", kSecAttrAccount as String: localTeamId]
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var add = query
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            guard SecItemAdd(add as CFDictionary, nil) == errSecSuccess else { throw CocoaError(.fileWriteUnknown) }
        } else if status != errSecSuccess { throw CocoaError(.fileWriteUnknown) }
    }
}

/// No redirects: avoid sending owner secrets to a different origin.
private final class RegistryNoRedirect: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
private let registryNoRedirect = RegistryNoRedirect()
private struct RegistryTransport: Sendable {
    let endpoint: String
    let key: String?
    /// Consensus bodies come from the KMP facade; never re-encode them as Swift strings.
    func callRaw(_ method: String, _ path: String, body: String? = nil) async throws -> String {
        guard let base = URL(string: endpoint), base.scheme == "https" ||
            (base.scheme == "http" && ["localhost", "127.0.0.1"].contains(base.host ?? "")),
              let url = URL(string: path, relativeTo: base)?.absoluteURL,
              url.host == base.host, url.scheme == base.scheme else { throw URLError(.unsupportedURL) }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 10
        if let key { request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization") }
        if let body { request.httpBody = Data(body.utf8); request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let session = URLSession(configuration: .ephemeral, delegate: registryNoRedirect, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw RegistryHTTPError(code: (response as? HTTPURLResponse)?.statusCode ?? -1)
        }
        return String(decoding: data, as: UTF8.self)
    }
    /// HTTP status for outbox policy: connection/transport failure is zero (retry, never refuse).
    func statusOf(_ method: String, _ path: String, body: String?) async -> Int {
        do { _ = try await callRaw(method, path, body: body); return 200 }
        catch let error as RegistryHTTPError { return error.code }
        catch { return -1 }
    }
    func call<T: Decodable & Sendable, P: Encodable & Sendable>(
        _ method: String, _ path: String, body: P?
    ) async throws -> T {
        guard let base = URL(string: endpoint), base.scheme == "https" ||
            (base.scheme == "http" && ["localhost", "127.0.0.1"].contains(base.host ?? "")) else {
            throw URLError(.unsupportedURL)
        }
        guard let url = URL(string: path, relativeTo: base)?.absoluteURL,
              url.host == base.host, url.scheme == base.scheme else { throw URLError(.unsupportedURL) }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 10
        if let key { request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization") }
        if let body { request.httpBody = try JSONEncoder().encode(body); request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let config = URLSessionConfiguration.ephemeral
        let session = URLSession(configuration: config, delegate: registryNoRedirect, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        guard (200...299).contains(http.statusCode) else { throw RegistryHTTPError(code: http.statusCode) }
        return try JSONDecoder().decode(T.self, from: data)
    }
}
private enum ConsensusOutboxStore {
    static func load(_ endpoint: String, _ link: String, _ team: String) throws -> String? {
        guard let url = fileURL(endpoint, link, team) else { throw CocoaError(.fileReadUnknown) }
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let data = try Data(contentsOf: url)
        guard let text = String(data: data, encoding: .utf8) else { throw CocoaError(.fileReadCorruptFile) }
        return text
    }
    static func save(_ endpoint: String, _ link: String, _ team: String, _ json: String) throws {
        guard let url = fileURL(endpoint, link, team) else { throw CocoaError(.fileWriteUnknown) }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(json.utf8).write(to: url, options: .atomic)
    }
    private static func fileURL(_ endpoint: String, _ link: String, _ team: String) -> URL? {
        let raw = "\(endpoint)|\(link)|\(team)"
        let digest = SHA256.hash(data: Data(raw.utf8)).map { String(format: "%02x", $0) }.joined()
        guard let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else { return nil }
        return dir.appendingPathComponent("pitchpact/consensus-\(digest).json")
    }
}

private struct RegistryHTTPError: LocalizedError { let code: Int; var errorDescription: String? { "Registry HTTP \(code)" } }

@MainActor struct RegistryView: View {
    @State private var endpoint = UserDefaults.standard.string(forKey: "registry.endpoint") ?? ""
    @State private var teams: [PitchPactStore.Team] = []
    @State private var selectedTeamId = ""
    @State private var creds: Credentials?
    @State private var region = ""
    @State private var ageGroup = ""
    @State private var query = ""
    @State private var found: [ListedTeam] = []
    @State private var code = ""
    @State private var inbox: [LinkRequest] = []
    @State private var links: [TeamLink] = []
    @State private var proposals: [SharedFixture] = []
    @State private var schedule: [SharedFixture] = []
    @State private var kickoff = Date().addingTimeInterval(86_400)
    @State private var location = ""
    @State private var homeUniform = ""
    @State private var awayUniform = ""
    @State private var feedback = ""
    private let rules = CoordinationFacade()

    var body: some View {
        Form {
            serverSection
            teamSection
            if let creds {
                findSection(creds)
                inboxSection(creds)
                linksSections(creds)
                scheduleSection(creds)
            }
            if !feedback.isEmpty { Text(feedback).foregroundStyle(.orange) }
        }
        .navigationTitle("Team registry")
        .onAppear {
            teams = (try? AppTeamStore.shared.teams()) ?? []
            if selectedTeamId.isEmpty { selectedTeamId = teams.first?.id ?? "" }
        }
    }
    @ViewBuilder private var serverSection: some View {
        Section("Server") {
            Text("Self-hosted registry. Enter your HTTPS server URL; no public deployment is assumed.")
            TextField("https://your-server.example", text: $endpoint)
                .textInputAutocapitalization(.never).keyboardType(.URL)
                .onChange(of: endpoint) { _, v in UserDefaults.standard.set(v, forKey: "registry.endpoint") }
        }
    }
    @ViewBuilder private var teamSection: some View {
        Section("My team") {
            Picker("Local team", selection: $selectedTeamId) {
                Text("Select team").tag("")
                ForEach(teams) { Text($0.name).tag($0.id) }
            }
            .onChange(of: selectedTeamId) { _, v in do { creds = try RegistrySecrets.load(v) } catch { feedback = error.localizedDescription } }
            if let team = teams.first(where: { $0.id == selectedTeamId }) {
                if let creds {
                    Text("Share team code: \(creds.teamCode)").textSelection(.enabled)
                    ShareLink("Share team code", item: creds.teamCode)
                    Button("Rotate code (old one dies now)") { run {
                        let code: TeamCode = try await transport.call("POST", "/v0/registry/teams/\(creds.team.id)/code/rotate", body: Optional<Int>.none)
                        var next = creds; next.teamCode = code.teamCode; try RegistrySecrets.save(selectedTeamId, next); self.creds = next
                    } }
                    Button(creds.team.listed ? "Hide public listing" : "Show public listing") { run {
                        let next: ListedTeam = try await transport.call("PUT", "/v0/registry/teams/\(creds.team.id)/listing",
                            body: Visibility(listed: !creds.team.listed))
                        var current = creds; current.team = next; try RegistrySecrets.save(selectedTeamId, current); self.creds = current
                    } }
                } else {
                    Text("Opt in before team appears in registry; only name, region and age group become visible.")
                    TextField("Region", text: $region)
                    TextField("Age group (e.g. U10)", text: $ageGroup)
                    Button("Opt in & get team code") { run {
                        let result: Credentials = try await RegistryTransport(endpoint: endpoint, key: nil).call("POST", "/v0/registry/teams",
                            body: Create(listing: Listing(clubName: team.name, region: region, ageGroup: ageGroup),
                                         idempotencyKey: UUID().uuidString))
                        try RegistrySecrets.save(selectedTeamId, result); creds = result
                    } }
                }
            } else { Text("Create a local team first") }
        }
    }
    @ViewBuilder private func findSection(_ creds: Credentials) -> some View {
        Section("Find teams") {
            TextField("Name, region or age group", text: $query)
            Button("Search registry") { run {
                let q = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
                found = try await transport.call("GET", "/v0/registry/teams?q=\(q)", body: Optional<Int>.none)
            } }
            ForEach(found, id: \.id) { team in
                Text("\(team.listing.clubName) · \(team.listing.region) · \(team.listing.ageGroup)")
            }
            TextField("Other team's TEAM-… code", text: $code).textInputAutocapitalization(.characters)
            Button("Request link") { run {
                let _: LinkRequest = try await transport.call("POST", "/v0/registry/teams/\(code)/link-requests",
                    body: RequestLink(requesterTeamId: creds.team.id, idempotencyKey: UUID().uuidString))
                feedback = "Link request sent"
            } }
        }
    }
    @ViewBuilder private func inboxSection(_ creds: Credentials) -> some View {
        Section("Incoming links") {
            Button("Refresh inbox & schedule") { run { try await refresh(creds) } }
            ForEach(inbox.filter { $0.state == "pending" }, id: \.id) { request in
                Text("Incoming request · \(request.requesterTeamId)")
                Button("Accept link") { run {
                    let _: TeamLink = try await transport.call("POST", "/v0/registry/links/\(request.id)/accept", body: Optional<Int>.none)
                    try await refresh(creds)
                } }
            }
        }
    }
    @ViewBuilder private func linksSections(_ creds: Credentials) -> some View {
        ForEach(links.filter { $0.state == "active" }, id: \.id) { link in
            linkedSection(link, creds: creds)
        }
    }
    @ViewBuilder private func linkedSection(_ link: TeamLink, creds: Credentials) -> some View {
            Section("Linked · \(link.id)") {
                Button("Review fixture proposals") { run { proposals = try await transport.call("GET", "/v0/links/\(link.id)/fixtures", body: Optional<Int>.none) } }
                proposalRows(link, creds: creds)
                scorerSection(link, creds: creds)
                proposalForm(link)
            }
    }
    @ViewBuilder private func proposalRows(_ link: TeamLink, creds: Credentials) -> some View {
        ForEach(proposals.filter { $0.linkId == link.id && $0.state == "proposed" }) { fixture in
            Text("\(fixture.location) · \(Date(timeIntervalSince1970: Double(fixture.startEpochMillis) / 1000).formatted())")
            Text("Home: \(fixture.homeUniform) · Away: \(fixture.awayUniform)")
            Button("Confirm fixture") { run {
                let _: SharedFixture = try await transport.call("POST", "/v0/links/\(link.id)/fixtures/\(fixture.id)/confirm", body: Optional<Int>.none)
                proposals = try await transport.call("GET", "/v0/links/\(link.id)/fixtures", body: Optional<Int>.none)
                try await refresh(creds)
            } }
        }
    }
    private func scorerSection(_ link: TeamLink, creds: Credentials) -> some View {
        ConsensusSection(link: link, myTeamId: creds.team.id, transport: transport, endpoint: endpoint,
                         onSnapshot: { raw in try applyConsensus(linkId: link.id, linkHomeTeamId: link.firstTeamId, snapshots: [raw]) })
            .id("\(endpoint)|\(link.id)|\(creds.team.id)")
    }
    @ViewBuilder private func proposalForm(_ link: TeamLink) -> some View {
        DatePicker("Kickoff", selection: $kickoff)
        TextField("Location", text: $location)
        TextField("Home uniform", text: $homeUniform)
        TextField("Away uniform", text: $awayUniform)
        Button("Propose fixture (needs both confirms)") { run {
            let proposal = Propose(startEpochMillis: Int64(kickoff.timeIntervalSince1970 * 1000),
                location: location, homeUniform: homeUniform, awayUniform: awayUniform,
                idempotencyKey: UUID().uuidString)
            let text = String(decoding: try JSONEncoder().encode(proposal), as: UTF8.self)
            let verdict = rules.validateProposal(payload: text, now: Int64(Date().timeIntervalSince1970 * 1000))
            guard verdict == "OK" else { throw RegistryHTTPError(code: 400) }
            let _: SharedFixture = try await transport.call("POST", "/v0/links/\(link.id)/fixtures", body: proposal)
            proposals = try await transport.call("GET", "/v0/links/\(link.id)/fixtures", body: Optional<Int>.none)
        } }
    }
    @ViewBuilder private func scheduleSection(_ creds: Credentials) -> some View {
        Section("Coordinated schedule") {
            Text("Only fixtures confirmed by both teams appear below or in local schedule.")
            Button("Sync confirmed games locally") { run {
                schedule = try await transport.call("GET", "/v0/registry/teams/\(creds.team.id)/schedule", body: Optional<Int>.none)
                try syncLocal(schedule, teamId: creds.team.id)
                for link in links where link.state == "active" {
                    var raws: [String] = []
                    for fixture in schedule where fixture.linkId == link.id {
                        do { raws.append(try await transport.callRaw("GET", "/v0/links/\(link.id)/consensus?matchId=\(fixture.id)")) }
                        catch let error as RegistryHTTPError where error.code == 404 { continue }
                    }
                    try applyConsensus(linkId: link.id, linkHomeTeamId: link.firstTeamId, snapshots: raws)
                }
            } }
            ForEach(schedule) { fixture in
                Text("\(fixture.location) · \(Date(timeIntervalSince1970: Double(fixture.startEpochMillis) / 1000).formatted())")
                Text("Home \(fixture.homeUniform) / Away \(fixture.awayUniform)")
            }
        }
    }
    private var transport: RegistryTransport { RegistryTransport(endpoint: endpoint, key: creds?.ownerKey) }
    private func run(_ work: @escaping @MainActor () async throws -> Void) {
        Task { @MainActor in do { try await work(); if feedback.isEmpty { feedback = "Updated" } }
            catch { feedback = error.localizedDescription } }
    }
    private func refresh(_ c: Credentials) async throws {
        inbox = try await transport.call("GET", "/v0/registry/teams/\(c.team.id)/link-requests", body: Optional<Int>.none)
        links = try await transport.call("GET", "/v0/registry/teams/\(c.team.id)/links", body: Optional<Int>.none)
        schedule = try await transport.call("GET", "/v0/registry/teams/\(c.team.id)/schedule", body: Optional<Int>.none)
    }
    /// Official results come only from the shared KMP rule over server snapshots (accepted-only).
    private func applyConsensus(linkId: String, linkHomeTeamId: String, snapshots: [String]) throws {
        guard !snapshots.isEmpty else { return }
        let current = try AppTeamStore.shared.fixtures().filter { $0.coordinatedLinkId == linkId }
        let currentJSON = String(decoding: try JSONEncoder().encode(current), as: UTF8.self)
        let updated = rules.applyConsensus(fixturesJson: currentJSON, linkId: linkId, linkHomeTeamId: linkHomeTeamId,
                                           snapshotsJson: "[" + snapshots.joined(separator: ",") + "]")
        let games = try JSONDecoder().decode([PitchPactStore.Fixture].self, from: Data(updated.utf8))
        for (before, after) in zip(current, games)
        where before.resultStatus != after.resultStatus || before.officialHome != after.officialHome || before.officialAway != after.officialAway {
            try AppTeamStore.shared.saveFixture(after)
        }
    }
    private func syncLocal(_ incoming: [SharedFixture], teamId: String) throws {
        let current = try AppTeamStore.shared.fixtures()
        let incomingJSON = String(decoding: try JSONEncoder().encode(incoming), as: UTF8.self)
        let currentJSON = String(decoding: try JSONEncoder().encode(current), as: UTF8.self)
        let projection = rules.mergeSchedule(scheduleJson: incomingJSON, teamId: teamId, existingFixturesJson: currentJSON)
        let merged = try JSONDecoder().decode(ScheduleProjection.self, from: Data(projection.utf8))
        try AppTeamStore.shared.removeCoordinatedFixtures(ids: merged.removedFixtureIds)
        for loc in merged.locations { try AppTeamStore.shared.saveLocation(loc) }
        for game in merged.fixtures { try AppTeamStore.shared.saveFixture(game) }
    }
}

/// M7 two-sideline scorer panel. Transport only: side mapping, wire bodies, labels,
/// official-vs-pending text and ledger wording all come from the shared KMP facade.
@MainActor private struct ConsensusSection: View {
    let link: TeamLink
    let myTeamId: String
    let transport: RegistryTransport
    let endpoint: String
    var onSnapshot: (String) throws -> Void = { _ in }
    @State private var matchId = ""
    @State private var jersey = ""
    @State private var snapshot = ""
    @State private var message = ""
    @State private var outbox = ""
    @State private var outboxLoaded = false
    @State private var outboxStatus = ""
    private let rules = ConsensusFacade()
    private var side: String { (try? rules.sideFor(firstTeamId: link.firstTeamId, secondTeamId: link.secondTeamId, myTeamId: myTeamId)) ?? "HOME" }

    var body: some View {
        Group {
            Text("Consensus scoring · \(side.lowercased()) sideline").font(.headline)
            if !outboxStatus.isEmpty {
                Text("Offline outbox: \(outboxStatus)").accessibilityIdentifier("consensus.outbox")
            } else if !outbox.isEmpty {
                Text("Offline outbox: \((try? rules.outboxSummary(outboxJson: outbox)) ?? "unavailable")").accessibilityIdentifier("consensus.outbox")
            }
            TextField("Match ID (shared fixture ID)", text: $matchId)
                .textInputAutocapitalization(.never).accessibilityIdentifier("consensus.matchId")
            TextField("Scorer jersey #", text: $jersey).keyboardType(.numberPad).accessibilityIdentifier("consensus.jersey")
            Button("Submit goal for opponent review") { run {
                let body = try rules.submitGoal(matchId: matchId, side: side, changeId: UUID().uuidString, eventId: UUID().uuidString,
                    epoch: Int64(Date().timeIntervalSince1970 * 1000), jersey: Int32(jersey) ?? -1, idempotencyKey: UUID().uuidString)
                try await send("/v0/links/\(link.id)/consensus/changes", body)
                try await refresh()
            } }.disabled(matchId.isEmpty || jersey.isEmpty).accessibilityIdentifier("consensus.submit")
            Button("Refresh official score") { run { try await refresh() } }.disabled(matchId.isEmpty)
            if !snapshot.isEmpty {
                Text((try? rules.scoreboard(snapshotJson: snapshot)) ?? "Scoreboard unavailable").accessibilityIdentifier("consensus.scoreboard")
                // Own accepted goals may be removed or corrected — always as a NEW pending decision.
                ForEach((try? rules.acceptedGoalRows(snapshotJson: snapshot, side: side)) ?? [], id: \.self) { row in
                    let parts = row.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
                    if parts.count == 2 {
                        let targetId = parts[0]
                        HStack {
                            Text("\(parts[1]) (your sideline)")
                            Button("Remove") { run {
                                let body = try rules.changeGoal(snapshotJson: snapshot, side: side, targetId: targetId,
                                    action: "remove", changeId: UUID().uuidString, eventId: targetId, replacementId: targetId,
                                    epoch: Int64(Date().timeIntervalSince1970 * 1000), jersey: -1,
                                    idempotencyKey: UUID().uuidString)
                                try await send("/v0/links/\(link.id)/consensus/changes", body)
                                try await refresh()
                            } }.accessibilityIdentifier("consensus.remove")
                            Button("Correct to #\(jersey)") { run {
                                let replacementId = UUID().uuidString
                                let body = try rules.changeGoal(snapshotJson: snapshot, side: side, targetId: targetId,
                                    action: "correct", changeId: UUID().uuidString, eventId: UUID().uuidString,
                                    replacementId: replacementId, epoch: Int64(Date().timeIntervalSince1970 * 1000),
                                    jersey: Int32(jersey) ?? -1, idempotencyKey: UUID().uuidString)
                                try await send("/v0/links/\(link.id)/consensus/changes", body)
                                try await refresh()
                            } }.disabled(jersey.isEmpty).accessibilityIdentifier("consensus.correct")
                        }
                    }
                }
                ForEach((try? rules.pendingRows(snapshotJson: snapshot, mySide: side)) ?? [], id: \.self) { row in
                    let parts = row.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
                    if parts.count == 3 {
                        Text(parts[1])
                        if parts[2] == "review" {
                            Button("Accept") { run { try await review(parts[0], "accept") } }.accessibilityIdentifier("consensus.accept")
                            Button("Reject") { run { try await review(parts[0], "reject") } }.accessibilityIdentifier("consensus.reject")
                        } else {
                            Button("Withdraw") { run { try await review(parts[0], "withdraw") } }.accessibilityIdentifier("consensus.withdraw")
                        }
                    }
                }
                Button("Final whistle (both sides required)") { run {
                    try await send("/v0/links/\(link.id)/consensus/whistle",
                        rules.whistleBody(matchId: matchId, side: side, idempotencyKey: UUID().uuidString))
                    try await refresh()
                } }
                Text("Record ledger (rejected and withdrawn changes are kept forever)").font(.caption.bold())
                Text((try? rules.recordLedger(snapshotJson: snapshot)) ?? "").font(.caption).accessibilityIdentifier("consensus.ledger")
            }
            if !message.isEmpty { Text(message).foregroundStyle(.orange) }
        }
        .onAppear {
            do { try loadOutbox() }
            catch { message = error.localizedDescription }
        }
    }
    private func run(_ work: @escaping @MainActor () async throws -> Void) {
        Task { @MainActor in do { try await work(); message = "" } catch { message = error.localizedDescription } }
    }
    /// Commit to the durable outbox BEFORE the first network attempt; replays reuse the same body/key.
    /// A queue that cannot be read or written is a hard stop: queued consensus writes must never
    /// be silently skipped or replaced (restart-replay contract).
    private func send(_ path: String, _ body: String) async throws {
        try loadOutbox()
        let next = try rules.outboxEnqueue(outboxJson: outbox, id: UUID().uuidString, path: path, body: body)
        try ConsensusOutboxStore.save(endpoint, link.id, myTeamId, next)
        outbox = next
        try await flush()
    }
    private func loadOutbox() throws {
        guard !outboxLoaded else { return }
        do {
            outbox = try ConsensusOutboxStore.load(endpoint, link.id, myTeamId) ?? rules.outboxNew()
            outboxStatus = ""
            outboxLoaded = true
        } catch {
            outboxStatus = "unreadable — scoring stopped"
            throw CocoaError(.fileReadCorruptFile, userInfo: [NSLocalizedDescriptionKey:
                "Consensus outbox unreadable; restore the device file before scoring"])
        }
    }
    private func flush() async throws {
        while true {
            let entry = try rules.outboxHead(outboxJson: outbox)
            if entry.isEmpty { return }
            let id = try rules.outboxId(entryJson: entry)
            let path = try rules.outboxPath(entryJson: entry)
            let body = try rules.outboxBody(entryJson: entry)
            let status = await transport.statusOf("POST", path, body: body)
            let next: String
            switch status {
            case 200...299: next = try rules.outboxDelivered(outboxJson: outbox, id: id)
            case 400...499: next = try rules.outboxRefuse(outboxJson: outbox, id: id, status: Int32(status))
            default: return // offline/server failure: exact body and key retained for retry
            }
            try ConsensusOutboxStore.save(endpoint, link.id, myTeamId, next)
            outbox = next
        }
    }
    private func refresh() async throws {
        try loadOutbox()
        try await flush()
        guard matchId.range(of: "^[A-Za-z0-9:_-]{1,128}$", options: .regularExpression) != nil else { throw URLError(.badURL) }
        snapshot = try await transport.callRaw("GET", "/v0/links/\(link.id)/consensus?matchId=\(matchId)")
        // Accepted-only official state also feeds the local schedule/standings (shared KMP rule).
        try onSnapshot(snapshot)
    }
    private func review(_ changeId: String, _ action: String) async throws {
        try await send("/v0/links/\(link.id)/consensus/changes/\(changeId)/\(action)",
            rules.reviewBody(idempotencyKey: UUID().uuidString))
        try await refresh()
    }
}
