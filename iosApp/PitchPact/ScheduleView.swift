import SwiftUI
import UserNotifications
import PitchPactStore
@preconcurrency import PitchPactShared

private typealias Team = PitchPactStore.Team
private typealias Fixture = PitchPactStore.Fixture
private typealias Tournament = PitchPactStore.Tournament
private typealias TournamentFormat = PitchPactStore.TournamentFormat
private typealias Availability = PitchPactStore.Availability
private typealias AvailabilityResponse = PitchPactStore.AvailabilityResponse
private typealias Player = PitchPactStore.Player
private typealias ScheduleConflict = PitchPactStore.ScheduleConflict
private typealias DraftPairing = PitchPactStore.DraftPairing
private typealias Standing = PitchPactStore.Standing
private typealias AvailabilityRollup = PitchPactStore.AvailabilityRollup

/// Native SwiftUI view. All scheduling decisions call shared SchedulingFacade; GRDB only stores private/local records.
struct ScheduleView: View {
    @State private var teams: [Team] = []
    @State private var locations: [GameLocation] = []
    @State private var tournaments: [Tournament] = []
    @State private var fixtures: [Fixture] = []
    @State private var title = ""
    @State private var place = ""
    @State private var selectedLocationId = ""
    @State private var editingLocationId: String?
    @State private var editingTournamentId: String?
    @State private var editingFixtureId: String?
    @State private var tournamentName = ""
    @State private var tournamentFormat: TournamentFormat = .ROUND_ROBIN
    @State private var home = ""
    @State private var away = ""
    @State private var tournamentId = ""
    @State private var kickoff = Date().addingTimeInterval(86_400)
    @State private var reminder = false
    @State private var selectedFixtureId: String?
    @State private var showPastGames = false
    @State private var durationMinutes = 90
    @State private var feedback = ""
    private let rules = SchedulingFacade()

    var body: some View {
        Form {
            Section("Games") {
                Toggle("Show past games", isOn: $showPastGames)
                ForEach(fixtures.filter { showPastGames || $0.startEpochMillis >= Int64(Date().timeIntervalSince1970 * 1000) }) { fixture in
                    Button {
                        selectedFixtureId = fixture.id
                    } label: {
                        VStack(alignment: .leading) {
                            HStack {
                                Text(fixture.title)
                                Spacer()
                                if fixture.startEpochMillis < Int64(Date().timeIntervalSince1970 * 1000) {
                                    Text("Past").font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            Text(Date(timeIntervalSince1970: Double(fixture.startEpochMillis) / 1000), style: .date)
                            if fixture.resultStatus == .PENDING { Text("Result pending review").foregroundStyle(.orange) }
                        }
                    }
                }
            }
            if let game = fixtures.first(where: { $0.id == selectedFixtureId }) {
                Section("Availability · \(game.title)") {
                    let roster = ((try? AppTeamStore.shared.players(teamId: game.homeTeamId)) ?? []) +
                        ((try? AppTeamStore.shared.players(teamId: game.awayTeamId)) ?? [])
                    let responses = (try? AppTeamStore.shared.availability(fixtureId: game.id)) ?? []
                    let summary = sharedRollup(game.id, roster, responses)
                    Text("Yes \(summary?.yes ?? 0) · No \(summary?.no ?? 0) · Maybe \(summary?.maybe ?? 0) · Unanswered \(summary?.unanswered ?? 0)")
                    ForEach(roster) { player in
                        Picker("#\(player.jerseyNumber) \(player.name)", selection: Binding(
                            get: { responses.first(where: { $0.playerId == player.id })?.choice },
                            set: { choice in
                                if let choice {
                                    do { try AppTeamStore.shared.saveAvailability(AvailabilityResponse(fixtureId: game.id, playerId: player.id, choice: choice)); reload() }
                                    catch { feedback = error.localizedDescription }
                                }
                            }
                        )) {
                            Text("Unanswered").tag(Availability?.none)
                            ForEach(Availability.allCases, id: \.self) { choice in Text(choice.rawValue).tag(Optional(choice)) }
                        }
                    }
                    Button("Edit game") {
                        editingFixtureId = game.id; title = game.title; home = game.homeTeamId; away = game.awayTeamId
                        tournamentId = game.tournamentId ?? ""; selectedLocationId = game.locationId ?? ""
                        kickoff = Date(timeIntervalSince1970: Double(game.startEpochMillis) / 1000)
                        reminder = game.reminderMinutesBefore != nil
                    }
                    Button("Delete game", role: .destructive) {
                        try? AppTeamStore.shared.deleteFixture(id: game.id)
                        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [game.id])
                        selectedFixtureId = nil; reload()
                    }
                }
            }
            Section("Locations") {
                ForEach(locations) { location in
                    HStack {
                        Text(location.name)
                        Spacer()
                        Button("Edit") { editingLocationId = location.id; place = location.name }
                        Button("Delete", role: .destructive) { try? AppTeamStore.shared.deleteLocation(id: location.id); reload() }
                    }
                }
                TextField("Field name or address", text: $place)
                Button(editingLocationId == nil ? "Add location" : "Save location") {
                    guard !place.trimmingCharacters(in: .whitespaces).isEmpty else { return }
                    try? AppTeamStore.shared.saveLocation(GameLocation(id: editingLocationId ?? UUID().uuidString, name: place))
                    place = ""; editingLocationId = nil; reload()
                }
            }
            Section("Tournaments") {
                ForEach(tournaments) { tournament in
                    VStack(alignment: .leading) {
                        Text(tournament.name).font(.headline)
                        ForEach(Array(sharedDraft(tournament).enumerated()), id: \.offset) { item in
                            Text("Round \(item.element.round): \(teamName(item.element.homeTeamId)) vs \(teamName(item.element.awayTeamId))")
                        }
                        ForEach(sharedStandings(tournament), id: \.teamId) { standing in
                            Text("\(teamName(standing.teamId)): \(standing.points) pts · \(standing.played) official played" +
                                (standing.pendingFixtures > 0 ? " (\(standing.pendingFixtures) pending review)" : ""))
                        }
                        Button("Edit tournament") {
                            editingTournamentId = tournament.id; tournamentName = tournament.name
                            tournamentFormat = tournament.format
                        }
                        Button("Delete tournament", role: .destructive) {
                            try? AppTeamStore.shared.deleteTournament(id: tournament.id); reload()
                        }
                    }
                }
                TextField("Tournament name", text: $tournamentName)
                Picker("Format", selection: $tournamentFormat) {
                    ForEach(TournamentFormat.allCases, id: \.self) { format in Text(format.rawValue).tag(format) }
                }
                Button(editingTournamentId == nil ? "Create tournament (all active teams)" : "Save tournament") {
                    guard teams.count >= 2, !tournamentName.isEmpty else { return }
                    let ids = tournaments.first(where: { $0.id == editingTournamentId })?.teamIds ?? teams.map(\.id)
                    try? AppTeamStore.shared.saveTournament(Tournament(id: editingTournamentId ?? UUID().uuidString,
                        name: tournamentName, teamIds: ids, format: tournamentFormat))
                    tournamentName = ""; editingTournamentId = nil; reload()
                }
            }
            Section("New fixture · tournament or ad-hoc") {
                TextField("Game title", text: $title)
                Picker("Home", selection: $home) {
                    Text("Choose").tag("")
                    ForEach(teams) { Text($0.name).tag($0.id) }
                }
                Picker("Away", selection: $away) {
                    Text("Choose").tag("")
                    ForEach(teams) { Text($0.name).tag($0.id) }
                }
                Picker("Location", selection: $selectedLocationId) {
                    Text("Unspecified").tag("")
                    ForEach(locations) { Text($0.name).tag($0.id) }
                }
                Picker("Competition", selection: $tournamentId) {
                    Text("Ad-hoc league").tag("")
                    ForEach(tournaments) { Text($0.name).tag($0.id) }
                }
                DatePicker("Kickoff", selection: $kickoff)
                Toggle("Remind me one hour before (local)", isOn: $reminder)
                Button("Save game", action: saveGame)
                if !feedback.isEmpty { Text(feedback).foregroundStyle(.orange) }
            }
        }
        .navigationTitle("Schedule")
        .onAppear(perform: reload)
    }

    private func json<T: Encodable>(_ value: T) -> String {
        String(data: (try? JSONEncoder().encode(value)) ?? Data(), encoding: .utf8) ?? ""
    }
    private func decode<T: Decodable>(_ text: String, _: T.Type) -> T? {
        try? JSONDecoder().decode(T.self, from: Data(text.utf8))
    }
    private func teamName(_ id: String?) -> String {
        guard let id else { return "BYE/TBD" }
        return teams.first(where: { $0.id == id })?.name ?? "Unknown team"
    }
    private func sharedDraft(_ tournament: Tournament) -> [DraftPairing] {
        decode(rules.draft(teamIds: tournament.teamIds, format: tournament.format.rawValue), [DraftPairing].self) ?? []
    }
    private func sharedStandings(_ tournament: Tournament) -> [Standing] {
        decode(rules.standings(teamIds: tournament.teamIds,
            fixturesJson: json(fixtures.filter { $0.tournamentId == tournament.id })), [Standing].self) ?? []
    }
    private func sharedRollup(_ fixtureId: String, _ roster: [Player], _ responses: [AvailabilityResponse]) -> AvailabilityRollup? {
        decode(rules.rollup(fixtureId: fixtureId, playerIds: roster.map(\.id), responsesJson: json(responses)), AvailabilityRollup.self)
    }
    private func saveGame() {
        let prior = fixtures.first(where: { $0.id == editingFixtureId })
        let game = Fixture(id: editingFixtureId ?? UUID().uuidString, title: title, homeTeamId: home, awayTeamId: away,
            startEpochMillis: Int64(kickoff.timeIntervalSince1970 * 1000),
            locationId: selectedLocationId.isEmpty ? nil : selectedLocationId,
            tournamentId: tournamentId.isEmpty ? nil : tournamentId,
            resultStatus: prior?.resultStatus ?? .UNSCORED,
            officialHome: prior?.officialHome, officialAway: prior?.officialAway,
            reminderMinutesBefore: reminder ? 60 : nil)
        let encoded = json(game)
        // Calling shared rules first makes invalid fixtures impossible to save; the warning names the existing clash.
        do {
            let validation = rules.validateEdit(previousJson: prior.map { json($0) } ?? "null", nextJson: encoded)
            guard validation == "OK" else { feedback = validation; return }
            let conflicts = decode(rules.conflicts(fixtureJson: encoded, existingJson: json(fixtures)), [ScheduleConflict].self) ?? []
            try AppTeamStore.shared.saveFixture(game)
            UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [game.id])
            feedback = conflicts.isEmpty ? "Saved" : conflicts.map(\.reason).joined(separator: "; ")
            if reminder {
                let timestamp = rules.reminderTimestamp(fixtureJson: encoded)
                let fireDate = Date(timeIntervalSince1970: Double(timestamp) / 1000)
                if fireDate > Date() {
                    UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { granted, _ in
                        guard granted else { return }
                        let content = UNMutableNotificationContent()
                        content.title = "Upcoming game"; content.body = game.title
                        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, fireDate.timeIntervalSinceNow), repeats: false)
                        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: game.id, content: content, trigger: trigger))
                    }
                }
            }
            title = ""; editingFixtureId = nil; reload()
        } catch { feedback = error.localizedDescription }
    }
    private func reload() {
        teams = (try? AppTeamStore.shared.teams()) ?? []
        locations = (try? AppTeamStore.shared.locations()) ?? []
        tournaments = (try? AppTeamStore.shared.tournaments()) ?? []
        fixtures = (try? AppTeamStore.shared.fixtures()) ?? []
    }
}
