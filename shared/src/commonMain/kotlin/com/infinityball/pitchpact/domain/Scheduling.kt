package com.infinityball.pitchpact.domain

import kotlinx.serialization.Serializable

/** Local scheduling data. No roster or availability model belongs in a public DTO. */
@Serializable data class Location(val id: String, val name: String, val address: String = "")
@Serializable enum class TournamentFormat { ROUND_ROBIN, SINGLE_ELIMINATION }
@Serializable data class Tournament(val id: String, val name: String, val teamIds: List<String>, val format: TournamentFormat)
@Serializable enum class ResultStatus { UNSCORED, PENDING, OFFICIAL }
@Serializable data class Fixture(
    val id: String, val title: String, val homeTeamId: String, val awayTeamId: String,
    val startEpochMillis: Long, val durationMinutes: Int, val locationId: String?,
    val tournamentId: String? = null, val round: Int? = null,
    val resultStatus: ResultStatus = ResultStatus.UNSCORED,
    val officialHome: Int? = null, val officialAway: Int? = null,
    val reminderMinutesBefore: Int? = null,
)
@Serializable enum class Availability { YES, NO, MAYBE }
/** PRIVATE: must not be included in broadcast/registry payloads. */
@Serializable data class AvailabilityResponse(val fixtureId: String, val playerId: String, val choice: Availability)
@Serializable data class AvailabilityRollup(val yes: Int, val no: Int, val maybe: Int, val unanswered: Int)
@Serializable data class DraftPairing(val round: Int, val homeTeamId: String?, val awayTeamId: String?)
@Serializable data class ScheduleConflict(val fixtureId: String, val fixtureTitle: String, val reason: String)
@Serializable data class Standing(val teamId: String, val played: Int, val points: Int, val goalsFor: Int, val goalsAgainst: Int)

/** Deterministic pure scheduling rules; UI calls these instead of reimplementing them. */
object Scheduling {
    fun validate(f: Fixture) {
        if (f.title.isBlank()) throw ValidationException("fixture.title.blank", "Fixture title is required")
        if (f.homeTeamId.isBlank() || f.awayTeamId.isBlank() || f.homeTeamId == f.awayTeamId)
            throw ValidationException("fixture.teams.invalid", "Choose two distinct teams")
        if (f.durationMinutes !in 1..1440) throw ValidationException("fixture.duration.invalid", "Duration must be 1..1440 minutes")
        if (f.reminderMinutesBefore != null && f.reminderMinutesBefore !in 0..10080)
            throw ValidationException("fixture.reminder.invalid", "Reminder must be within seven days")
        if (f.resultStatus == ResultStatus.OFFICIAL && (f.officialHome == null || f.officialAway == null || f.officialHome < 0 || f.officialAway < 0))
            throw ValidationException("fixture.result.invalid", "Official result requires nonnegative accepted scores")
    }

    fun conflicts(candidate: Fixture, others: List<Fixture>): List<ScheduleConflict> {
        validate(candidate)
        val end = candidate.startEpochMillis + candidate.durationMinutes * 60_000L
        return others.filter { other ->
            other.id != candidate.id && candidate.startEpochMillis < other.startEpochMillis + other.durationMinutes * 60_000L && other.startEpochMillis < end
        }.mapNotNull { other ->
            val team = listOf(candidate.homeTeamId, candidate.awayTeamId).any { it == other.homeTeamId || it == other.awayTeamId }
            val field = candidate.locationId != null && candidate.locationId == other.locationId
            if (team || field) ScheduleConflict(other.id, other.title, when {
                team && field -> "Team and field overlap with ${other.title}"
                team -> "Team overlap with ${other.title}"
                else -> "Field overlap with ${other.title}"
            }) else null
        }
    }

    /** UTC instants from native date pickers; elapsed-time subtraction survives DST jumps. */
    fun reminderEpochMillis(f: Fixture): Long? = f.reminderMinutesBefore?.let {
        f.startEpochMillis - it * 60_000L
    }

    fun rollup(fixtureId: String, playerIds: List<String>, responses: List<AvailabilityResponse>): AvailabilityRollup {
        val current = responses.filter { it.fixtureId == fixtureId }.associateBy { it.playerId }
        val choices = playerIds.distinct().map { current[it]?.choice }
        return AvailabilityRollup(choices.count { it == Availability.YES }, choices.count { it == Availability.NO },
            choices.count { it == Availability.MAYBE }, choices.count { it == null })
    }

    fun draft(teamIds: List<String>, format: TournamentFormat): List<DraftPairing> {
        require(teamIds.size >= 2 && teamIds.distinct().size == teamIds.size) { "Need two or more distinct teams" }
        if (format == TournamentFormat.ROUND_ROBIN) {
            val slots: MutableList<String?> = teamIds.map { it as String? }.toMutableList()
            if (slots.size % 2 != 0) slots.add(null)
            val result = mutableListOf<DraftPairing>()
            repeat(slots.size - 1) { r ->
                repeat(slots.size / 2) { i ->
                    val a = slots[i]; val b = slots[slots.lastIndex - i]
                    result.add(DraftPairing(r + 1, a, b)) // explicit bye, never fake fixture
                }
                slots.add(1, slots.removeAt(slots.lastIndex))
            }
            return result
        }
        // Reviewable seeded bracket: BYE entries are explicit in round 1;
        // later rounds use empty placeholders, never pretend to know a winner.
        val size = (1..16).firstOrNull { it >= teamIds.size && it and (it - 1) == 0 }
            ?: error("Bracket supports up to 16 teams")
        val byes = size - teamIds.size
        val result = mutableListOf<DraftPairing>()
        teamIds.take(byes).forEach { result.add(DraftPairing(1, it, null)) }
        teamIds.drop(byes).chunked(2).forEach { result.add(DraftPairing(1, it[0], it[1])) }
        var games = size / 4; var round = 2
        while (games > 0) { repeat(games) { result.add(DraftPairing(round, null, null)) }; games /= 2; round++ }
        return result
    }

    /** Never transform a provisional/pending score into 0–0. */
    fun standings(teamIds: List<String>, fixtures: List<Fixture>): List<Standing> = teamIds.map { id ->
        val games = fixtures.filter { it.resultStatus == ResultStatus.OFFICIAL && it.officialHome != null && it.officialAway != null && (it.homeTeamId == id || it.awayTeamId == id) }
        Standing(id, games.size, games.sumOf { f ->
            val own = if (f.homeTeamId == id) f.officialHome!! else f.officialAway!!
            val against = if (f.homeTeamId == id) f.officialAway!! else f.officialHome!!
            if (own > against) 3 else if (own == against) 1 else 0
        }, games.sumOf { if (it.homeTeamId == id) it.officialHome!! else it.officialAway!! },
            games.sumOf { if (it.homeTeamId == id) it.officialAway!! else it.officialHome!! })
    }.sortedWith(compareByDescending<Standing> { it.points }.thenByDescending { it.goalsFor - it.goalsAgainst }.thenBy { it.teamId })
}
