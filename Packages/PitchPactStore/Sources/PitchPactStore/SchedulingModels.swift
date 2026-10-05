import Foundation

/// Persistence-only Swift mirrors of shared Kotlin scheduling models; rules are called via SchedulingFacade.
public struct GameLocation: Codable, Identifiable, Sendable { public var id: String; public var name: String; public var address: String
    public init(id: String, name: String, address: String = "") { self.id = id; self.name = name; self.address = address }
}
public enum TournamentFormat: String, Codable, CaseIterable, Sendable { case ROUND_ROBIN, SINGLE_ELIMINATION }
public struct Tournament: Codable, Identifiable, Sendable {
    public var id: String; public var name: String; public var teamIds: [String]; public var format: TournamentFormat
    public init(id: String, name: String, teamIds: [String], format: TournamentFormat) {
        self.id = id; self.name = name; self.teamIds = teamIds; self.format = format
    }
}
public enum ResultStatus: String, Codable, Sendable { case UNSCORED, PENDING, OFFICIAL }
public struct Fixture: Codable, Identifiable, Sendable {
    public var id: String; public var title: String; public var homeTeamId: String; public var awayTeamId: String
    public var startEpochMillis: Int64; public var durationMinutes: Int; public var locationId: String?
    public var tournamentId: String?; public var round: Int?; public var resultStatus: ResultStatus
    public var officialHome: Int?; public var officialAway: Int?; public var reminderMinutesBefore: Int?
    public init(id: String, title: String, homeTeamId: String, awayTeamId: String,
                startEpochMillis: Int64, durationMinutes: Int = 90, locationId: String? = nil,
                tournamentId: String? = nil, round: Int? = nil, resultStatus: ResultStatus = .UNSCORED,
                officialHome: Int? = nil, officialAway: Int? = nil, reminderMinutesBefore: Int? = nil) {
        self.id = id; self.title = title; self.homeTeamId = homeTeamId; self.awayTeamId = awayTeamId
        self.startEpochMillis = startEpochMillis; self.durationMinutes = durationMinutes
        self.locationId = locationId; self.tournamentId = tournamentId; self.round = round
        self.resultStatus = resultStatus; self.officialHome = officialHome; self.officialAway = officialAway
        self.reminderMinutesBefore = reminderMinutesBefore
    }
}
public enum Availability: String, Codable, CaseIterable, Sendable { case YES, NO, MAYBE }
/// PRIVATE: do not include in public DTOs.
public struct AvailabilityResponse: Codable, Sendable {
    public var fixtureId: String; public var playerId: String; public var choice: Availability
    public init(fixtureId: String, playerId: String, choice: Availability) {
        self.fixtureId = fixtureId; self.playerId = playerId; self.choice = choice
    }
}
public struct DraftPairing: Codable, Sendable {
    public var round: Int; public var homeTeamId: String?; public var awayTeamId: String?
}
public struct ScheduleConflict: Codable, Sendable {
    public var fixtureId: String; public var fixtureTitle: String; public var reason: String
}
public struct AvailabilityRollup: Codable, Sendable {
    public var yes: Int; public var no: Int; public var maybe: Int; public var unanswered: Int
}
public struct Standing: Codable, Sendable {
    public var teamId: String; public var played: Int; public var points: Int; public var goalsFor: Int; public var goalsAgainst: Int
}
