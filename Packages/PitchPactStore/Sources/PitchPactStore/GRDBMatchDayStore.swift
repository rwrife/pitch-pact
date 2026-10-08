import Foundation
import GRDB

// Opaque shared JSON: platform store never derives business rules. One row is the
// atomic ledger + outbox + clock checkpoint, including all appended corrections.
extension GRDBTeamStore {
    public func matchDay(id: String) throws -> String? {
        try writer.read { db in
            try String.fetchOne(db, sql: "SELECT payload FROM match_day WHERE id = ?", arguments: [id])
        }
    }
    public func saveMatchDay(id: String, sharedJSON: String) throws {
        guard !id.isEmpty, let data = sharedJSON.data(using: .utf8),
              let value = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              value["matchId"] as? String == id else {
            throw ValidationFailure("match.invalid", "Invalid shared snapshot")
        }
        try writer.write { db in
            try db.execute(sql: "INSERT INTO match_day (id, payload) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload",
                           arguments: [id, sharedJSON])
        }
    }
}
