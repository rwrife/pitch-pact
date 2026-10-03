import Foundation

// MARK: - Shared-mirroring value types
//
// These mirror the Kotlin `shared` DTOs field-for-field (same JSON key
// names, same enum wire spellings). The Kotlin module stays the product
// truth; the app-facing Swift surface cannot import Kotlin/Native types in
// pure unit tests, so parity is asserted by identical serialization and
// identical validation error codes.

public struct Team: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var name: String
    public var createdAtEpochMillis: Int64
    public var archivedAtEpochMillis: Int64?

    public init(
        id: String, name: String,
        createdAtEpochMillis: Int64, archivedAtEpochMillis: Int64? = nil
    ) {
        self.id = id
        self.name = name
        self.createdAtEpochMillis = createdAtEpochMillis
        self.archivedAtEpochMillis = archivedAtEpochMillis
    }

    public var isArchived: Bool { archivedAtEpochMillis != nil }
}

public struct Player: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var teamId: String
    public var name: String
    public var jerseyNumber: Int
    public var createdAtEpochMillis: Int64

    public init(
        id: String, teamId: String, name: String,
        jerseyNumber: Int, createdAtEpochMillis: Int64
    ) {
        self.id = id
        self.teamId = teamId
        self.name = name
        self.jerseyNumber = jerseyNumber
        self.createdAtEpochMillis = createdAtEpochMillis
    }
}

/// Lowercase raw values match the Kotlin @SerialName spellings exactly.
public enum KitColor: String, Codable, CaseIterable, Sendable {
    case white, black, red, blue, green, yellow, orange, purple, pink, grey
}

public struct Kit: Codable, Equatable, Sendable {
    public var primary: KitColor
    public var alternate: KitColor?

    public init(primary: KitColor, alternate: KitColor? = nil) {
        self.primary = primary
        self.alternate = alternate
    }
}

public struct UniformRequirement: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var teamId: String
    public var opponentName: String
    public var homeKit: Kit
    public var awayKit: Kit
    public var keeperKit: Kit?
    public var equipmentChecklist: [String]
    public var notes: String

    public init(
        id: String, teamId: String, opponentName: String,
        homeKit: Kit, awayKit: Kit, keeperKit: Kit? = nil,
        equipmentChecklist: [String] = [], notes: String = ""
    ) {
        self.id = id
        self.teamId = teamId
        self.opponentName = opponentName
        self.homeKit = homeKit
        self.awayKit = awayKit
        self.keeperKit = keeperKit
        self.equipmentChecklist = equipmentChecklist
        self.notes = notes
    }
}

/// PRIVATE youth-privacy type (M2): exists only locally; must never be
/// encoded into any broadcast/registry payload (privacy serialization tests
/// live in the shared Kotlin module; this package adds the store-level test).
public struct GuardianContact: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var playerId: String
    public var guardianName: String
    public var phone: String
    public var email: String
    public var notes: String

    public init(
        id: String, playerId: String, guardianName: String,
        phone: String = "", email: String = "", notes: String = ""
    ) {
        self.id = id
        self.playerId = playerId
        self.guardianName = guardianName
        self.phone = phone
        self.email = email
        self.notes = notes
    }
}

public struct TeamDeletionPreview: Equatable, Sendable {
    public var teamId: String
    public var playerCount: Int
    public var uniformRequirementCount: Int
    public var guardianContactCount: Int
    public var fixtureCount: Int

    public init(
        teamId: String, playerCount: Int, uniformRequirementCount: Int,
        guardianContactCount: Int, fixtureCount: Int = 0
    ) {
        self.teamId = teamId
        self.playerCount = playerCount
        self.uniformRequirementCount = uniformRequirementCount
        self.guardianContactCount = guardianContactCount
        self.fixtureCount = fixtureCount
    }

    public var blockingCount: Int {
        playerCount + uniformRequirementCount + guardianContactCount + fixtureCount
    }
}

/// Error codes + wording copied verbatim from the Kotlin `Validate` object so
/// both platforms render identical text.
public struct ValidationFailure: Error, Equatable, Sendable {
    public let code: String
    public let message: String

    public init(_ code: String, _ message: String) {
        self.code = code
        self.message = message
    }
}

public enum StoreValidation {
    public static func jerseyNumber(_ number: Int) throws {
        guard (1...99).contains(number) else {
            throw ValidationFailure("jersey.out_of_range", "Jersey number must be 1..99, got \(number)")
        }
    }

    public static func jerseyUnique(roster: [Player], _ player: Player) throws {
        try jerseyNumber(player.jerseyNumber)
        if let clash = roster.first(where: { $0.id != player.id && $0.jerseyNumber == player.jerseyNumber }) {
            throw ValidationFailure(
                "jersey.clash",
                "Jersey #\(player.jerseyNumber) already worn by \(clash.name)"
            )
        }
    }

    public static func teamName(_ name: String) throws {
        guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw ValidationFailure("team.name.blank", "Team name is required")
        }
    }

    public static func playerName(_ name: String) throws {
        guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw ValidationFailure("player.name.blank", "Player name is required")
        }
    }

    public static func opponentName(_ name: String) throws {
        guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw ValidationFailure("uniform.opponent.blank", "Opponent name is required")
        }
    }

    public static func guardianName(_ name: String) throws {
        guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw ValidationFailure("guardian.name.blank", "Guardian name is required")
        }
    }

    public static func guardianReachable(_ contact: GuardianContact) throws {
        guard !contact.phone.trimmingCharacters(in: .whitespaces).isEmpty
            || !contact.email.trimmingCharacters(in: .whitespaces).isEmpty
        else {
            throw ValidationFailure("guardian.unreachable", "Guardian needs a phone or email")
        }
    }

    public static func normalizedChecklist(_ lines: [String]) -> [String] {
        var seen = Set<String>()
        return lines
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .filter { seen.insert($0).inserted }
    }
}
