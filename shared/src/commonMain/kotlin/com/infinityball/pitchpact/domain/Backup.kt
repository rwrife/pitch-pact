package com.infinityball.pitchpact.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Private, offline-only data format. Never pass this through registry or broadcast DTOs. */
@Serializable
data class BackupV1(
    val version: Int = 1,
    val teams: List<Team> = emptyList(),
    val players: List<Player> = emptyList(),
    val guardianContacts: List<GuardianContact> = emptyList(),
    val uniforms: List<UniformRequirement> = emptyList(),
    val locations: List<Location> = emptyList(),
    val tournaments: List<Tournament> = emptyList(),
    val fixtures: List<Fixture> = emptyList(),
    val availability: List<AvailabilityResponse> = emptyList(),
    val matches: List<MatchDayState> = emptyList(),
)

@Serializable
data class BackupCounts(
    val teams: Int, val players: Int, val guardianContacts: Int, val uniforms: Int,
    val locations: Int, val tournaments: Int, val fixtures: Int, val availability: Int,
    val matches: Int, val ledgerEvents: Int, val consensusDecisions: Int,
)

/** Decode and validate before presenting replacement preview; caller owns atomic platform transaction. */
object BackupCodec {
    private val json = Json { ignoreUnknownKeys = false }

    fun encode(backup: BackupV1): String {
        validate(backup)
        return json.encodeToString(BackupV1.serializer(), backup)
    }

    fun decode(data: String): BackupV1 = json.decodeFromString(BackupV1.serializer(), data).also(::validate)

    fun preview(data: String): BackupCounts = counts(decode(data))

    fun counts(b: BackupV1) = BackupCounts(
        b.teams.size, b.players.size, b.guardianContacts.size, b.uniforms.size,
        b.locations.size, b.tournaments.size, b.fixtures.size, b.availability.size,
        b.matches.size, b.matches.sumOf { it.events.size }, b.matches.sumOf { it.decisions.size },
    )

    fun validate(b: BackupV1) {
        require(b.version == 1) { "Unsupported backup version: ${b.version}" }
        fun <T> unique(rows: List<T>, id: (T) -> String): Set<String> {
            val ids = rows.map(id)
            require(ids.all(String::isNotBlank) && ids.size == ids.toSet().size) { "Blank or duplicate backup ID" }
            return ids.toSet()
        }
        val teams = unique(b.teams, Team::id)
        val players = unique(b.players, Player::id)
        unique(b.guardianContacts, GuardianContact::id)
        unique(b.uniforms, UniformRequirement::id)
        val locations = unique(b.locations, Location::id)
        val tournaments = unique(b.tournaments, Tournament::id)
        val fixtures = unique(b.fixtures, Fixture::id)
        unique(b.matches, MatchDayState::matchId)
        require(b.players.all { it.teamId in teams }) { "Backup player has no team" }
        require(b.guardianContacts.all { it.playerId in players }) { "Backup guardian has no player" }
        require(b.uniforms.all { it.teamId in teams }) { "Backup uniform has no team" }
        require(b.tournaments.all { t -> t.teamIds.all { it in teams } }) { "Backup tournament has no team" }
        require(b.fixtures.all { f ->
            if (f.coordinatedLinkId != null) f.homeTeamId.isNotBlank() && f.awayTeamId.isNotBlank() && f.homeTeamId != f.awayTeamId
            else f.homeTeamId in teams && f.awayTeamId in teams
        } && b.fixtures.all { f ->
            (f.locationId == null || f.locationId in locations || f.locationId.startsWith("coordinated-location:")) &&
                (f.tournamentId == null || f.tournamentId in tournaments)
        }) { "Backup fixture has missing relation" }
        b.fixtures.forEach(Scheduling::validate)
        require(b.availability.all { it.fixtureId in fixtures && it.playerId in players }) {
            "Backup availability has missing relation"
        }
        require(b.availability.map { it.fixtureId to it.playerId }.distinct().size == b.availability.size) {
            "Duplicate backup availability"
        }
        require(b.matches.all { state -> state.matchId in fixtures &&
            runCatching { state.officialScore() }.isSuccess }) { "Invalid backup match ledger or consensus" }
    }
}
