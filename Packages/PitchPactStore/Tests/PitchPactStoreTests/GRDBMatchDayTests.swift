import Foundation
import Testing
import GRDB
@testable import PitchPactStore

struct GRDBMatchDayTests {
    @Test func restartAndAtomicSnapshot() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".sqlite")
        defer { try? FileManager.default.removeItem(at: url) }
        let json = #"{"matchId":"m","events":[{"eventId":"g"}],"queuedIds":["g"],"clock":{"elapsedMillis":1234}}"#
        do { let store = try GRDBTeamStore(url: url); try store.saveMatchDay(id: "m", sharedJSON: json) }
        let restored = try GRDBTeamStore(url: url)
        #expect(try restored.matchDay(id: "m") == json)
        #expect(throws: (any Error).self) { try restored.saveMatchDay(id: "m", sharedJSON: #"{"matchId":"different"}"#) }
        #expect(try restored.matchDay(id: "m") == json)
        try restored.writer.write { db in
            try db.execute(sql: "CREATE TRIGGER reject_match BEFORE UPDATE ON match_day BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        }
        #expect(throws: (any Error).self) {
            try restored.saveMatchDay(id: "m", sharedJSON: #"{"matchId":"m","events":[],"queuedIds":[],"clock":{}}"#)
        }
        #expect(try restored.matchDay(id: "m") == json)
    }
}
