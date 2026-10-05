import Foundation
import GRDB

// Platform persistence only: all validation/conflicts/standings are delegated to shared SchedulingFacade by SwiftUI.
// JSON payloads keep schema v2 forward-compatible; PRIVATE availability never enters public DTO queries.
extension GRDBTeamStore {
    private func encode<T: Encodable>(_ value: T) throws -> String {
        let bytes = try JSONEncoder().encode(value)
        guard let text = String(data: bytes, encoding: .utf8) else { throw ValidationFailure("json.invalid", "Could not encode") }
        return text
    }
    private func records<T: Decodable>(_ type: T.Type, table: String) throws -> [T] {
        try writer.read { db in
            try String.fetchAll(db, sql: "SELECT payload FROM \(table) ORDER BY id")
                .map { try JSONDecoder().decode(type, from: Data($0.utf8)) }
        }
    }
    private func upsert<T: Encodable>(_ value: T, id: String, table: String) throws {
        let payload = try encode(value)
        try writer.write { db in
            try db.execute(sql: "INSERT INTO \(table) (id, payload) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload",
                           arguments: [id, payload])
        }
    }
    private func remove(id: String, table: String) throws {
        try writer.write { db in try db.execute(sql: "DELETE FROM \(table) WHERE id = ?", arguments: [id]) }
    }
    public func saveLocation(_ location: GameLocation) throws { try upsert(location, id: location.id, table: "location") }
    public func locations() throws -> [GameLocation] { try records(GameLocation.self, table: "location") }
    public func deleteLocation(id: String) throws { try remove(id: id, table: "location") }
    public func saveTournament(_ tournament: Tournament) throws { try upsert(tournament, id: tournament.id, table: "tournament") }
    public func tournaments() throws -> [Tournament] { try records(Tournament.self, table: "tournament") }
    public func deleteTournament(id: String) throws { try remove(id: id, table: "tournament") }
    public func saveFixture(_ fixture: Fixture) throws { try upsert(fixture, id: fixture.id, table: "fixture") }
    public func fixtures() throws -> [Fixture] { try records(Fixture.self, table: "fixture").sorted { $0.startEpochMillis < $1.startEpochMillis } }
    public func deleteFixture(id: String) throws {
        try writer.write { db in
            try db.execute(sql: "DELETE FROM availability WHERE fixture_id = ?", arguments: [id])
            try db.execute(sql: "DELETE FROM fixture WHERE id = ?", arguments: [id])
        }
    }
    public func saveAvailability(_ response: AvailabilityResponse) throws {
        guard try players(teamId: try fixtureTeamId(response.fixtureId)).contains(where: { $0.id == response.playerId }) ||
            (try fixtures().first(where: { $0.id == response.fixtureId }).map { try players(teamId: $0.awayTeamId).contains(where: { $0.id == response.playerId }) } ?? false)
        else { throw ValidationFailure("rsvp.player.missing", "Player must belong to a fixture team") }
        try writer.write { db in
            try db.execute(sql: "INSERT INTO availability (fixture_id, player_id, choice) VALUES (?, ?, ?) ON CONFLICT(fixture_id, player_id) DO UPDATE SET choice=excluded.choice",
                           arguments: [response.fixtureId, response.playerId, response.choice.rawValue])
        }
    }
    private func fixtureTeamId(_ id: String) throws -> String {
        guard let f = try fixtures().first(where: { $0.id == id }) else {
            throw ValidationFailure("rsvp.fixture.missing", "Fixture not found")
        }
        return f.homeTeamId
    }
    public func availability(fixtureId: String) throws -> [AvailabilityResponse] {
        try writer.read { db in
            let rows = try Row.fetchAll(db, sql: "SELECT player_id, choice FROM availability WHERE fixture_id = ? ORDER BY player_id", arguments: [fixtureId])
            return rows.compactMap { row in
                guard let choice = Availability(rawValue: row["choice"]) else { return nil }
                return AvailabilityResponse(fixtureId: fixtureId, playerId: row["player_id"], choice: choice)
            }
        }
    }
}
