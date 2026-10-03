package com.infinityball.pitchpact.store

import com.infinityball.pitchpact.domain.GuardianContact
import com.infinityball.pitchpact.domain.Player
import com.infinityball.pitchpact.domain.Team
import com.infinityball.pitchpact.domain.TeamDeletionPreview
import com.infinityball.pitchpact.domain.UniformRequirement

/**
 * Platform store contract (M2). iOS implements it on GRDB, Android on Room,
 * both at schema v1 with versioned migrations, and BOTH round-trip the same
 * shared fixture JSON (see shared fixture round-trip tests).
 *
 * Single-threaded by contract: implementations are driven from the UI thread.
 * All read methods return lists newest-first unless noted.
 */
interface TeamStore {
    /** Schema version actually applied by the store (v1 today). */
    fun schemaVersion(): Long

    // --- Teams ---
    fun saveTeam(team: Team)
    fun team(id: String): Team?
    fun teams(includeArchived: Boolean = false): List<Team>
    fun archiveTeam(id: String, archived: Boolean, atEpochMillis: Long)

    /**
     * Deletion guard: NEVER destroys anything. Returns counts of dependent
     * rows so the UI can show an explicit cascade preview before a confirmed
     * [deleteTeam].
     */
    fun deletionPreview(id: String): TeamDeletionPreview

    /**
     * Confirmed destructive delete. Throws if [requireEmpty] and dependent
     * rows exist — the UI must pass false only after showing the preview.
     */
    fun deleteTeam(id: String, requireEmpty: Boolean = true)

    // --- Players ---
    fun savePlayer(player: Player)
    fun player(id: String): Player?
    fun players(teamId: String): List<Player>

    /** Removes the player AND cascades their guardian contacts (one tx). */
    fun deletePlayer(id: String)

    // --- Uniform requirements ---
    fun saveUniformRequirement(requirement: UniformRequirement)
    fun uniformRequirements(teamId: String): List<UniformRequirement>
    fun deleteUniformRequirement(id: String)

    // --- Guardian contacts (private table; never exported to broadcast DTOs) ---
    fun saveGuardianContact(contact: GuardianContact)
    fun guardianContacts(playerId: String): List<GuardianContact>
    fun guardianContactsForTeam(teamId: String): List<GuardianContact>
    fun deleteGuardianContact(id: String)
}

/** Stable serialization used by both stores' round-trip fixture tests. */
object StoreFixtureCodec {
    fun encode(teams: List<Team>, players: List<Player>): String =
        kotlinx.serialization.json.Json.encodeToString(
            FixtureTeams.serializer(),
            FixtureTeams(teams, players),
        )

    fun decode(json: String): Pair<List<Team>, List<Player>> {
        val f = kotlinx.serialization.json.Json.decodeFromString(FixtureTeams.serializer(), json)
        return f.teams to f.players
    }

    @kotlinx.serialization.Serializable
    private data class FixtureTeams(
        val teams: List<Team>,
        val players: List<Player>,
    )
}
