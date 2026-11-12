import Foundation
import GRDB
import Testing
@testable import PitchPactStore

@Suite("M8 atomic local backup")
struct GRDBBackupStoreTests {
    @Test func roundTripCancelAndRollback() throws {
        let store = try GRDBTeamStore(inMemory: true)
        try store.saveTeam(Team(id: "home", name: "Falcons", createdAtEpochMillis: 1))
        try store.saveTeam(Team(id: "away", name: "Rivals", createdAtEpochMillis: 1))
        try store.savePlayer(Player(id: "p", teamId: "home", name: "Jo", jerseyNumber: 7, createdAtEpochMillis: 1))
        try store.saveGuardianContact(GuardianContact(id: "g", playerId: "p", guardianName: "Guardian", email: "private@example.org"))
        try store.saveFixture(Fixture(id: "f", title: "Cup", homeTeamId: "home", awayTeamId: "away", startEpochMillis: 1000))
        try store.saveAvailability(AvailabilityResponse(fixtureId: "f", playerId: "p", choice: .YES))
        let original = try store.backupJSON()
        let root = try #require(JSONSerialization.jsonObject(with: Data(original.utf8)) as? [String: Any])
        #expect(root["version"] as? Int == 1)
        #expect((root["guardianContacts"] as? [[String: Any]])?.count == 1)
        // Preview/cancel calls validator but never writes.
        try validate(original)
        #expect(try store.backupJSON() == original)
        try store.saveTeam(Team(id: "extra", name: "To remove", createdAtEpochMillis: 2))
        try store.replaceBackup(original, validate: validate)
        #expect(try store.backupJSON() == original)
        #expect(try store.guardianContacts(playerId: "p").first?.email == "private@example.org")
        try store.writer.write { db in
            try db.execute(sql: "CREATE TRIGGER reject_restore BEFORE INSERT ON fixture BEGIN SELECT RAISE(ABORT, 'injected restore failure'); END")
        }
        try store.saveTeam(Team(id: "keep", name: "Keep", createdAtEpochMillis: 3))
        let beforeFailure = try store.backupJSON()
        #expect(throws: (any Error).self) { try store.replaceBackup(original, validate: validate) }
        #expect(try store.backupJSON() == beforeFailure)
        #expect(throws: (any Error).self) { try store.replaceBackup("{}", validate: validate) }
        #expect(try store.backupJSON() == beforeFailure)
    }

    private func validate(_ json: String) throws {
        guard let value = try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any],
              value["version"] as? Int == 1 else {
            throw ValidationFailure("backup.invalid", "Unsupported backup")
        }
    }
}
