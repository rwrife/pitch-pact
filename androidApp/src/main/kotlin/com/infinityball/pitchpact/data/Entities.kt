package com.infinityball.pitchpact.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room schema v1 for M2. Table names/columns are deliberately aligned with
 * the iOS GRDB schema so both stores are described by one migration story.
 *
 * guardian_contact is its OWN table (private data, never joined into any
 * export/broadcast query).
 */
@Entity(
    tableName = "team",
)
data class TeamRow(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "archived_at", defaultValue = "NULL") val archivedAt: Long?,
)

@Entity(
    tableName = "player",
    indices = [Index(value = ["team_id", "jersey_number"], unique = true), Index("team_id")],
)
data class PlayerRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "team_id") val teamId: String,
    val name: String,
    @ColumnInfo(name = "jersey_number") val jerseyNumber: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "uniform_requirement",
    indices = [Index("team_id")],
)
data class UniformRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "team_id") val teamId: String,
    @ColumnInfo(name = "opponent_name") val opponentName: String,
    @ColumnInfo(name = "home_primary") val homePrimary: String,
    @ColumnInfo(name = "home_alternate") val homeAlternate: String?,
    @ColumnInfo(name = "away_primary") val awayPrimary: String,
    @ColumnInfo(name = "away_alternate") val awayAlternate: String?,
    @ColumnInfo(name = "keeper_primary") val keeperPrimary: String?,
    @ColumnInfo(name = "keeper_alternate") val keeperAlternate: String?,
    /** JSON array of trimmed checklist lines (shared codec owns the format). */
    @ColumnInfo(name = "equipment_json") val equipmentJson: String,
    val notes: String,
)

@Entity(
    tableName = "guardian_contact",
    indices = [Index("player_id")],
)
data class GuardianRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "player_id") val playerId: String,
    @ColumnInfo(name = "guardian_name") val guardianName: String,
    val phone: String,
    val email: String,
    val notes: String,
)
