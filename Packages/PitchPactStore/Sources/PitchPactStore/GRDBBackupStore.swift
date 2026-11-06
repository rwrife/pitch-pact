import Foundation
import GRDB

// Private device-only format. Shared BackupFacade validates all relations and
// consensus before replacement; this store owns only SQLite atomicity.
extension GRDBTeamStore {
    public func backupJSON() throws -> String {
        let teams = try teams(includeArchived: true)
        let players = try teams.flatMap { try self.players(teamId: $0.id) }
        let uniforms = try teams.flatMap { try uniformRequirements(teamId: $0.id) }
        let guardians = try players.flatMap { try guardianContacts(playerId: $0.id) }
        let fixtures = try fixtures()
        let availability = try fixtures.flatMap { try self.availability(fixtureId: $0.id) }
        let matches: [[String: Any]] = try writer.read { db in
            try String.fetchAll(db, sql: "SELECT payload FROM match_day ORDER BY id").map { payload in
                guard let value = try JSONSerialization.jsonObject(with: Data(payload.utf8)) as? [String: Any] else {
                    throw ValidationFailure("backup.match.invalid", "Match snapshot must be an object")
                }
                return value
            }
        }
        func object<T: Encodable>(_ value: T) throws -> Any {
            try JSONSerialization.jsonObject(with: JSONEncoder().encode(value), options: [.fragmentsAllowed])
        }
        let value: [String: Any] = [
            "version": 1, "teams": try object(teams), "players": try object(players),
            "guardianContacts": try object(guardians), "uniforms": try object(uniforms),
            "locations": try object(locations()), "tournaments": try object(tournaments()),
            "fixtures": try object(fixtures), "availability": try object(availability), "matches": matches
        ]
        return String(decoding: try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]), as: UTF8.self)
    }

    /// Validator must call shared BackupFacade.preview and throw on ERROR.
    /// It runs again at commit time; cancelled previews never reach this method.
    public func replaceBackup(_ json: String, validate: (String) throws -> Void) throws {
        try validate(json)
        let root = try JSONSerialization.jsonObject(with: Data(json.utf8))
        guard let fields = root as? [String: Any] else {
            throw ValidationFailure("backup.invalid", "Backup must be an object")
        }
        let decoder = JSONDecoder()
        func rows<T: Decodable>(_ key: String, as type: T.Type) throws -> T {
            guard let value = fields[key] else { throw ValidationFailure("backup.missing", "Missing \(key)") }
            return try decoder.decode(type, from: JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed]))
        }
        let teams = try rows("teams", as: [Team].self)
        let players = try rows("players", as: [Player].self)
        let guardians = try rows("guardianContacts", as: [GuardianContact].self)
        let uniforms = try rows("uniforms", as: [UniformRequirement].self)
        let locations = try rows("locations", as: [GameLocation].self)
        let tournaments = try rows("tournaments", as: [Tournament].self)
        let fixtures = try rows("fixtures", as: [Fixture].self)
        let availability = try rows("availability", as: [AvailabilityResponse].self)
        guard let snapshots = fields["matches"] as? [[String: Any]] else {
            throw ValidationFailure("backup.missing", "Missing matches")
        }
        let matches: [(String, String)] = try snapshots.map { snapshot in
            guard let id = snapshot["matchId"] as? String, !id.isEmpty else {
                throw ValidationFailure("backup.match.invalid", "Match ID missing")
            }
            return (id, String(decoding: try JSONSerialization.data(withJSONObject: snapshot, options: [.sortedKeys]), as: UTF8.self))
        }
        func payload<T: Encodable>(_ value: T) throws -> String {
            String(decoding: try JSONEncoder().encode(value), as: UTF8.self)
        }
        try writer.write { db in
            for table in ["match_day", "availability", "fixture", "tournament", "location", "guardian_contact", "uniform_requirement", "player", "team"] {
                try db.execute(sql: "DELETE FROM \(table)")
            }
            for team in teams { try team.row().insert(db) }
            for player in players { try player.row().insert(db) }
            for guardian in guardians { try guardian.row().insert(db) }
            for uniform in uniforms { try uniform.row().insert(db) }
            for location in locations {
                try db.execute(sql: "INSERT INTO location (id, payload) VALUES (?, ?)", arguments: [location.id, try payload(location)])
            }
            for tournament in tournaments {
                try db.execute(sql: "INSERT INTO tournament (id, payload) VALUES (?, ?)", arguments: [tournament.id, try payload(tournament)])
            }
            for fixture in fixtures {
                try db.execute(sql: "INSERT INTO fixture (id, payload) VALUES (?, ?)", arguments: [fixture.id, try payload(fixture)])
            }
            for response in availability {
                try db.execute(sql: "INSERT INTO availability (fixture_id, player_id, choice) VALUES (?, ?, ?)",
                               arguments: [response.fixtureId, response.playerId, response.choice.rawValue])
            }
            for (id, snapshot) in matches {
                try db.execute(sql: "INSERT INTO match_day (id, payload) VALUES (?, ?)", arguments: [id, snapshot])
            }
        }
    }
}
