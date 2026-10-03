package com.infinityball.pitchpact.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TeamDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertTeam(row: TeamRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertPlayer(row: PlayerRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertUniform(row: UniformRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertGuardian(row: GuardianRow)

    @Query("SELECT * FROM team WHERE id = :id")
    fun team(id: String): TeamRow?

    @Query("SELECT * FROM team WHERE (:includeArchived = 1 OR archived_at IS NULL) ORDER BY created_at DESC")
    fun teams(includeArchived: Boolean): List<TeamRow>

    @Query("UPDATE team SET archived_at = :at WHERE id = :id")
    fun setArchived(id: String, at: Long?)

    @Query("DELETE FROM team WHERE id = :id")
    fun deleteTeam(id: String)

    @Query("SELECT * FROM player WHERE id = :id")
    fun player(id: String): PlayerRow?

    @Query("SELECT * FROM player WHERE team_id = :teamId ORDER BY jersey_number ASC")
    fun players(teamId: String): List<PlayerRow>

    @Query("SELECT COUNT(*) FROM player WHERE team_id = :teamId")
    fun playerCount(teamId: String): Int

    @Query("DELETE FROM player WHERE id = :id")
    fun deletePlayer(id: String)

    @Query("DELETE FROM guardian_contact WHERE player_id = :playerId")
    fun deleteGuardiansForPlayer(playerId: String)

    @Query("SELECT * FROM uniform_requirement WHERE team_id = :teamId ORDER BY opponent_name COLLATE NOCASE ASC")
    fun uniforms(teamId: String): List<UniformRow>

    @Query("SELECT COUNT(*) FROM uniform_requirement WHERE team_id = :teamId")
    fun uniformCount(teamId: String): Int

    @Query("DELETE FROM uniform_requirement WHERE id = :id")
    fun deleteUniform(id: String)

    @Query("SELECT * FROM guardian_contact WHERE player_id = :playerId ORDER BY guardian_name COLLATE NOCASE ASC")
    fun guardians(playerId: String): List<GuardianRow>

    @Query(
        "SELECT g.* FROM guardian_contact g JOIN player p ON p.id = g.player_id " +
            "WHERE p.team_id = :teamId ORDER BY g.guardian_name COLLATE NOCASE ASC",
    )
    fun guardiansForTeam(teamId: String): List<GuardianRow>

    @Query("SELECT COUNT(*) FROM guardian_contact g JOIN player p ON p.id = g.player_id WHERE p.team_id = :teamId")
    fun guardianCountForTeam(teamId: String): Int

    @Query("DELETE FROM guardian_contact WHERE id = :id")
    fun deleteGuardian(id: String)
}
