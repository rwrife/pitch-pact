import Foundation
import GRDB

// MARK: - GRDB rows (schema v1, snake_case columns aligned with Android Room)
//
// Column names are pinned via explicit CodingKeys so the iOS schema matches
// the Android Room schema byte-for-byte (both use snake_case).

struct TeamRow: Codable, FetchableRecord, PersistableRecord {
    static let databaseTableName = "team"

    var id: String
    var name: String
    var createdAt: Int64
    var archivedAt: Int64?

    enum CodingKeys: String, CodingKey {
        case id, name
        case createdAt = "created_at"
        case archivedAt = "archived_at"
    }
}

struct PlayerRow: Codable, FetchableRecord, PersistableRecord {
    static let databaseTableName = "player"

    var id: String
    var teamId: String
    var name: String
    var jerseyNumber: Int
    var createdAt: Int64

    enum CodingKeys: String, CodingKey {
        case id, name
        case teamId = "team_id"
        case jerseyNumber = "jersey_number"
        case createdAt = "created_at"
    }
}

struct UniformRow: Codable, FetchableRecord, PersistableRecord {
    static let databaseTableName = "uniform_requirement"

    var id: String
    var teamId: String
    var opponentName: String
    var homePrimary: String
    var homeAlternate: String?
    var awayPrimary: String
    var awayAlternate: String?
    var keeperPrimary: String?
    var keeperAlternate: String?
    var equipmentJson: String
    var notes: String

    enum CodingKeys: String, CodingKey {
        case id, notes
        case teamId = "team_id"
        case opponentName = "opponent_name"
        case homePrimary = "home_primary"
        case homeAlternate = "home_alternate"
        case awayPrimary = "away_primary"
        case awayAlternate = "away_alternate"
        case keeperPrimary = "keeper_primary"
        case keeperAlternate = "keeper_alternate"
        case equipmentJson = "equipment_json"
    }
}

struct GuardianRow: Codable, FetchableRecord, PersistableRecord {
    static let databaseTableName = "guardian_contact"

    var id: String
    var playerId: String
    var guardianName: String
    var phone: String
    var email: String
    var notes: String

    enum CodingKeys: String, CodingKey {
        case id, phone, email, notes
        case playerId = "player_id"
        case guardianName = "guardian_name"
    }
}

/// Migration identifiers are stable strings; applied-set bookkeeping lives in
/// GRDB's own `grdb_migrations` table.
public enum PitchPactMigrations {
    public static let v1Teams = "v1-teams-rosters-uniforms"
    public static let v2Schedule = "v2-schedule-private-availability"

    public static var migrator: DatabaseMigrator {
        var migrator = DatabaseMigrator()

        migrator.registerMigration(v1Teams) { db in
            try db.create(table: TeamRow.databaseTableName) { t in
                t.column("id", .text).primaryKey()
                t.column("name", .text).notNull()
                t.column("created_at", .integer).notNull()
                t.column("archived_at", .integer)
            }

            try db.create(table: PlayerRow.databaseTableName) { t in
                t.column("id", .text).primaryKey()
                t.column("team_id", .text).notNull().indexed()
                    .references(TeamRow.databaseTableName, onDelete: .cascade)
                t.column("name", .text).notNull()
                t.column("jersey_number", .integer).notNull()
                t.column("created_at", .integer).notNull()
                t.uniqueKey(["team_id", "jersey_number"])
            }

            try db.create(table: UniformRow.databaseTableName) { t in
                t.column("id", .text).primaryKey()
                t.column("team_id", .text).notNull().indexed()
                    .references(TeamRow.databaseTableName, onDelete: .cascade)
                t.column("opponent_name", .text).notNull()
                t.column("home_primary", .text).notNull()
                t.column("home_alternate", .text)
                t.column("away_primary", .text).notNull()
                t.column("away_alternate", .text)
                t.column("keeper_primary", .text)
                t.column("keeper_alternate", .text)
                t.column("equipment_json", .text).notNull()
                t.column("notes", .text).notNull().defaults(to: "")
            }

            // PRIVATE table: guardian contacts live here alone and are never
            // joined into any export/broadcast query (youth-privacy contract).
            try db.create(table: GuardianRow.databaseTableName) { t in
                t.column("id", .text).primaryKey()
                t.column("player_id", .text).notNull().indexed()
                    .references(PlayerRow.databaseTableName, onDelete: .cascade)
                t.column("guardian_name", .text).notNull()
                t.column("phone", .text).notNull().defaults(to: "")
                t.column("email", .text).notNull().defaults(to: "")
                t.column("notes", .text).notNull().defaults(to: "")
            }
        }

        migrator.registerMigration(v2Schedule) { db in
            for table in ["location", "tournament", "fixture"] {
                try db.create(table: table) { t in
                    t.column("id", .text).primaryKey()
                    t.column("payload", .text).notNull()
                }
            }
            // Private: fixture/player RSVP rows are never exported to the server.
            try db.create(table: "availability") { t in
                t.column("fixture_id", .text).notNull()
                t.column("player_id", .text).notNull().indexed()
                    .references("player", onDelete: .cascade)
                t.column("choice", .text).notNull()
                t.primaryKey(["fixture_id", "player_id"])
            }
        }
        migrator.registerMigration("v3-match-day") { db in
            try db.create(table: "match_day") { t in
                t.column("id", .text).primaryKey()
                t.column("payload", .text).notNull()
            }
        }
        return migrator
    }
}

/// GRDB-backed team store — the iOS side of the M2 dual-store contract
/// (mirrors `shared/src/commonMain/.../store/TeamStore.kt` and Android's
/// RoomTeamStore, schema v1 on both platforms).
///
/// Single-threaded by contract: the SwiftUI layer drives this from the main
/// actor; DatabaseQueue serializes internally.
public final class GRDBTeamStore {
    let writer: any DatabaseWriter

    public init(url: URL) throws {
        writer = try DatabaseQueue(path: url.path)
        try PitchPactMigrations.migrator.migrate(writer)
    }

    /// In-memory store for previews and unit tests.
    public init(inMemory: Bool) throws {
        writer = try DatabaseQueue()
        try PitchPactMigrations.migrator.migrate(writer)
    }

    /// Schema version derived from the applied-migration registry
    /// (grdb_migrations has no meaningful ORDER — intersect our own list).
    public func schemaVersion() throws -> Int {
        try writer.read { db in
            let applied = Set(try String.fetchAll(
                db, sql: "SELECT identifier FROM grdb_migrations"
            ))
            var version = 0
            if applied.contains(PitchPactMigrations.v1Teams) { version = 1 }
            if applied.contains(PitchPactMigrations.v2Schedule) { version = 2 }
            if applied.contains("v3-match-day") { version = 3 }
            return version
        }
    }
}

// MARK: - TeamStore contract

extension GRDBTeamStore {
    public func saveTeam(_ team: Team) throws {
        try StoreValidation.teamName(team.name)
        try writer.write { db in try team.row().save(db) }
    }

    public func team(id: String) throws -> Team? {
        try writer.read { db in
            try TeamRow.fetchOne(db, key: id).map { $0.team }
        }
    }

    public func teams(includeArchived: Bool = false) throws -> [Team] {
        try writer.read { db in
            var request = TeamRow.all()
            if !includeArchived {
                request = request.filter(Column("archived_at") == nil)
            }
            return try request
                .order(Column("created_at").desc)
                .fetchAll(db)
                .map(\.team)
        }
    }

    public func archiveTeam(id: String, archived: Bool, atEpochMillis: Int64) throws {
        try writer.write { db in
            if archived {
                try db.execute(
                    sql: "UPDATE team SET archived_at = ? WHERE id = ?",
                    arguments: [atEpochMillis, id]
                )
            } else {
                try db.execute(
                    sql: "UPDATE team SET archived_at = NULL WHERE id = ?",
                    arguments: [id]
                )
            }
        }
    }

    public func deletionPreview(id: String) throws -> TeamDeletionPreview {
        let fixtureCount = try fixtures().filter { $0.homeTeamId == id || $0.awayTeamId == id }.count
        return try writer.read { db in
            TeamDeletionPreview(
                teamId: id,
                playerCount: try PlayerRow
                    .filter(Column("team_id") == id).fetchCount(db),
                uniformRequirementCount: try UniformRow
                    .filter(Column("team_id") == id).fetchCount(db),
                guardianContactCount: try Int.fetchOne(
                    db,
                    sql: """
                    SELECT COUNT(*) FROM guardian_contact g
                    JOIN player p ON p.id = g.player_id
                    WHERE p.team_id = ?
                    """,
                    arguments: [id]
                ) ?? 0,
                fixtureCount: fixtureCount
            )
        }
    }

    /// FK cascades (team -> player/uniform, player -> guardian) remove the
    /// dependents in one transaction. `requireEmpty` refuses WITHOUT
    /// destroying anything — the UI must show deletionPreview first.
    public func deleteTeam(id: String, requireEmpty: Bool = true) throws {
        let preview = try deletionPreview(id: id)
        if requireEmpty && preview.blockingCount > 0 {
            throw ValidationFailure(
                "team.not_empty",
                "Team still holds \(preview.playerCount) players, \(preview.uniformRequirementCount) uniforms, \(preview.guardianContactCount) guardian contacts"
            )
        }
        let dependentFixtures = try fixtures().filter { $0.homeTeamId == id || $0.awayTeamId == id }.map(\.id)
        try writer.write { db in
            for fixtureId in dependentFixtures {
                try db.execute(sql: "DELETE FROM availability WHERE fixture_id = ?", arguments: [fixtureId])
                try db.execute(sql: "DELETE FROM fixture WHERE id = ?", arguments: [fixtureId])
            }
            _ = try TeamRow.deleteOne(db, key: id)
        }
    }

    public func savePlayer(_ player: Player) throws {
        try StoreValidation.playerName(player.name)
        try writer.write { db in
            let roster = try PlayerRow.filter(Column("team_id") == player.teamId)
                .fetchAll(db).map(\.player)
            try StoreValidation.jerseyUnique(roster: roster, player)
            try player.row().save(db)
        }
    }

    public func player(id: String) throws -> Player? {
        try writer.read { db in try PlayerRow.fetchOne(db, key: id).map(\.player) }
    }

    public func players(teamId: String) throws -> [Player] {
        try writer.read { db in
            try PlayerRow.filter(Column("team_id") == teamId)
                .order(Column("jersey_number"))
                .fetchAll(db).map(\.player)
        }
    }

    public func deletePlayer(id: String) throws {
        // FK cascade removes guardian_contact rows in the same transaction.
        try writer.write { db in _ = try PlayerRow.deleteOne(db, key: id) }
    }

    public func saveUniformRequirement(_ requirement: UniformRequirement) throws {
        try StoreValidation.opponentName(requirement.opponentName)
        let normalized = requirement.withChecklist(
            StoreValidation.normalizedChecklist(requirement.equipmentChecklist)
        )
        try writer.write { db in try normalized.row().save(db) }
    }

    public func uniformRequirements(teamId: String) throws -> [UniformRequirement] {
        try writer.read { db in
            try UniformRow.filter(Column("team_id") == teamId)
                .order(sql: "opponent_name COLLATE NOCASE")
                .fetchAll(db).map { try $0.requirement() }
        }
    }

    public func deleteUniformRequirement(id: String) throws {
        try writer.write { db in _ = try UniformRow.deleteOne(db, key: id) }
    }

    public func saveGuardianContact(_ contact: GuardianContact) throws {
        try StoreValidation.guardianName(contact.guardianName)
        try StoreValidation.guardianReachable(contact)
        try writer.write { db in try contact.row().save(db) }
    }

    public func guardianContacts(playerId: String) throws -> [GuardianContact] {
        try writer.read { db in
            try GuardianRow.filter(Column("player_id") == playerId)
                .order(sql: "guardian_name COLLATE NOCASE")
                .fetchAll(db).map(\.contact)
        }
    }

    public func guardianContactsForTeam(teamId: String) throws -> [GuardianContact] {
        try writer.read { db in
            try GuardianRow.fetchAll(
                db,
                sql: """
                SELECT g.* FROM guardian_contact g
                JOIN player p ON p.id = g.player_id
                WHERE p.team_id = ?
                ORDER BY g.guardian_name COLLATE NOCASE
                """,
                arguments: [teamId]
            ).map(\.contact)
        }
    }

    public func deleteGuardianContact(id: String) throws {
        try writer.write { db in _ = try GuardianRow.deleteOne(db, key: id) }
    }
}

// MARK: - Row <-> domain mapping (no business rules here)

extension TeamRow {
    var team: Team {
        Team(id: id, name: name, createdAtEpochMillis: createdAt, archivedAtEpochMillis: archivedAt)
    }
}

extension PlayerRow {
    var player: Player {
        Player(
            id: id, teamId: teamId, name: name,
            jerseyNumber: jerseyNumber, createdAtEpochMillis: createdAt
        )
    }
}

extension UniformRow {
    func requirement() throws -> UniformRequirement {
        let checklist = try JSONDecoder().decode(
            [String].self, from: Data(equipmentJson.utf8)
        )
        return UniformRequirement(
            id: id, teamId: teamId, opponentName: opponentName,
            homeKit: Kit(
                primary: KitColor(rawValue: homePrimary) ?? .white,
                alternate: homeAlternate.flatMap(KitColor.init(rawValue:))
            ),
            awayKit: Kit(
                primary: KitColor(rawValue: awayPrimary) ?? .white,
                alternate: awayAlternate.flatMap(KitColor.init(rawValue:))
            ),
            keeperKit: keeperPrimary.map {
                Kit(
                    primary: KitColor(rawValue: $0) ?? .green,
                    alternate: keeperAlternate.flatMap(KitColor.init(rawValue:))
                )
            },
            equipmentChecklist: checklist,
            notes: notes
        )
    }
}

extension GuardianRow {
    var contact: GuardianContact {
        GuardianContact(id: id, playerId: playerId, guardianName: guardianName, phone: phone, email: email, notes: notes)
    }
}

extension Team {
    func row() -> TeamRow {
        TeamRow(id: id, name: name, createdAt: createdAtEpochMillis, archivedAt: archivedAtEpochMillis)
    }
}

extension Player {
    func row() -> PlayerRow {
        PlayerRow(id: id, teamId: teamId, name: name, jerseyNumber: jerseyNumber, createdAt: createdAtEpochMillis)
    }
}

extension UniformRequirement {
    func withChecklist(_ checklist: [String]) -> UniformRequirement {
        var copy = self
        copy.equipmentChecklist = checklist
        return copy
    }

    func row() -> UniformRow {
        UniformRow(
            id: id, teamId: teamId, opponentName: opponentName,
            homePrimary: homeKit.primary.rawValue, homeAlternate: homeKit.alternate?.rawValue,
            awayPrimary: awayKit.primary.rawValue, awayAlternate: awayKit.alternate?.rawValue,
            keeperPrimary: keeperKit?.primary.rawValue, keeperAlternate: keeperKit?.alternate?.rawValue,
            equipmentJson: (try? JSONEncoder().encode(equipmentChecklist))
                .flatMap { String(data: $0, encoding: .utf8) } ?? "[]",
            notes: notes
        )
    }
}

extension GuardianContact {
    func row() -> GuardianRow {
        GuardianRow(id: id, playerId: playerId, guardianName: guardianName, phone: phone, email: email, notes: notes)
    }
}
