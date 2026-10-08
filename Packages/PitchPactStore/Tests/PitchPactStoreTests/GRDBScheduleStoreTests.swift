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
    }

    @Test func standingsPendingFixturesRoundTrip() throws {
        let standing = Standing(teamId: "a", played: 1, points: 3, goalsFor: 2, goalsAgainst: 1, pendingFixtures: 2)
        let data = try JSONEncoder().encode(standing)
        let decoded = try JSONDecoder().decode(Standing.self, from: data)
        #expect(decoded.pendingFixtures == 2)
    }
}
