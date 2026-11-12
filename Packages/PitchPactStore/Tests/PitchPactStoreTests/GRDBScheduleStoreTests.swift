import Foundation
import Testing
@testable import PitchPactStore

@Suite("M3 private scheduling GRDB store")
struct GRDBScheduleStoreTests {
    @Test func fixturesAndRsvpPersistAndCascade() throws {
        let store = try GRDBTeamStore(inMemory: true)
        #expect(try store.schemaVersion() == 3)
        try store.saveTeam(Team(id: "a", name: "Falcons", createdAtEpochMillis: 1))
        try store.saveTeam(Team(id: "b", name: "Comets", createdAtEpochMillis: 1))
        try store.savePlayer(Player(id: "p", teamId: "a", name: "Jo", jerseyNumber: 7, createdAtEpochMillis: 1))
        try store.saveLocation(GameLocation(id: "l", name: "Field"))
        try store.saveTournament(Tournament(id: "cup", name: "Cup", teamIds: ["a", "b"], format: .ROUND_ROBIN))
        let fixture = Fixture(id: "f", title: "First", homeTeamId: "a", awayTeamId: "b",
            startEpochMillis: 1_000_000, locationId: "l", tournamentId: "cup")
        try store.saveFixture(fixture)
        #expect(try store.fixtures().map(\.id) == ["f"])
        #expect(try store.locations().map(\.name) == ["Field"])
        #expect(try store.tournaments().map(\.name) == ["Cup"])
        try store.saveAvailability(AvailabilityResponse(fixtureId: "f", playerId: "p", choice: .YES))
        #expect(try store.availability(fixtureId: "f").first?.choice == .YES)
        #expect(try store.deletionPreview(id: "a").fixtureCount == 1)
        #expect(throws: ValidationFailure.self) { try store.deleteTeam(id: "a") }
        try store.deleteTeam(id: "a", requireEmpty: false)
        #expect(try store.fixtures().isEmpty)
        #expect(try store.availability(fixtureId: "f").isEmpty)
        #expect(try store.tournaments().map(\.teamIds) == [["b"]])
        let backup = try store.backupJSON()
        let root = try #require(JSONSerialization.jsonObject(with: Data(backup.utf8)) as? [String: Any])
        #expect((root["teams"] as? [[String: Any]])?.count == 1)
        #expect(try store.teams(includeArchived: true).map(\.id) == ["b"])
    }

    @Test("fixture and team deletion remove match ledger, while archive preserves it")
    func ledgerDeletionScope() throws {
        let store = try GRDBTeamStore(inMemory: true)
        try store.saveTeam(Team(id: "a", name: "Falcons", createdAtEpochMillis: 1))
        try store.saveTeam(Team(id: "b", name: "Comets", createdAtEpochMillis: 1))
        for id in ["f1", "f2"] {
            try store.saveFixture(Fixture(id: id, title: id, homeTeamId: "a", awayTeamId: "b", startEpochMillis: 1))
            try store.saveMatchDay(id: id, sharedJSON: "{\"matchId\":\"\(id)\",\"events\":[]}")
        }
        try store.archiveTeam(id: "a", archived: true, atEpochMillis: 10)
        #expect(try store.matchDay(id: "f1") != nil)
        try store.deleteFixture(id: "f1")
        #expect(try store.matchDay(id: "f1") == nil)
        #expect(try store.matchDay(id: "f2") != nil)
        #expect(try store.deletionPreview(id: "a").fixtureCount == 1)
        try store.deleteTeam(id: "a", requireEmpty: false)
        #expect(try store.matchDay(id: "f2") == nil)
        #expect(try store.fixtures().isEmpty)
    }

    @Test("season deletion keeps coordinated and out-of-range games, cancels on preview")
    func seasonDeletion() throws {
        let store = try GRDBTeamStore(inMemory: true)
        for (id, at, link) in [("local", Int64(100), nil as String?), ("before", 99, nil),
                               ("after", 200, nil), ("shared", 150, "link")] {
            try store.saveFixture(Fixture(id: id, title: id, homeTeamId: "a", awayTeamId: "b",
                                          startEpochMillis: at, coordinatedLinkId: link))
            try store.saveMatchDay(id: id, sharedJSON: "{\"matchId\":\"\(id)\",\"events\":[]}")
        }
        #expect(try store.fixtures().count == 4) // cancel: preview is read-only
        try store.deleteSeason(startEpochMillis: 100, endEpochMillis: 200, expectedFixtureIds: ["local"])
        #expect(try store.fixtures().map(\.id).sorted() == ["after", "before", "shared"])
        #expect(try store.matchDay(id: "local") == nil)
        #expect(try store.matchDay(id: "shared") != nil)
        #expect(throws: ValidationFailure.self) {
            try store.deleteSeason(startEpochMillis: 100, endEpochMillis: 200, expectedFixtureIds: ["shared"])
        }
    }

    @Test func standingsPendingFixturesRoundTrip() throws {
        let standing = Standing(teamId: "a", played: 1, points: 3, goalsFor: 2, goalsAgainst: 1, pendingFixtures: 2)
        let data = try JSONEncoder().encode(standing)
        let decoded = try JSONDecoder().decode(Standing.self, from: data)
        #expect(decoded.pendingFixtures == 2)
    }
}

@Suite("M6 coordinated fixtures")
struct CoordinatedFixtureStoreTests {
    @Test("coordinated provenance round-trips and blocks local delete")
    func provenanceAndDeleteGuard() throws {
        let store = try GRDBTeamStore(inMemory: true)
        let game = Fixture(id: "coordinated:f1", title: "Shared match", homeTeamId: "a", awayTeamId: "b",
                           startEpochMillis: 1, coordinatedLinkId: "LNK-1", coordinatedRevision: 2)
        try store.saveFixture(game)
        let loaded = try #require(try store.fixtures().first)
        #expect(loaded.coordinatedLinkId == "LNK-1" && loaded.coordinatedRevision == 2)
        #expect(throws: ValidationFailure.self) { try store.deleteFixture(id: "coordinated:f1") }
        #expect(try store.fixtures().count == 1)
    }

    @Test("sync removal drops only coordinated projections")
    func syncRemovalScope() throws {
        let store = try GRDBTeamStore(inMemory: true)
        try store.saveFixture(Fixture(id: "coordinated:f1", title: "Shared", homeTeamId: "a", awayTeamId: "b",
                                      startEpochMillis: 1, coordinatedLinkId: "LNK-1", coordinatedRevision: 1))
        try store.saveFixture(Fixture(id: "local-1", title: "Scrimmage", homeTeamId: "a", awayTeamId: "c", startEpochMillis: 2))
        try store.removeCoordinatedFixtures(ids: ["coordinated:f1", "local-1"])
        #expect(try store.fixtures().map(\.id) == ["local-1"])
    }
}
