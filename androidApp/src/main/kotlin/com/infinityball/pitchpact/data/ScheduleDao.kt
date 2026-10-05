package com.infinityball.pitchpact.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "location") data class LocationRow(@PrimaryKey val id: String, val payload: String)
@Entity(tableName = "tournament") data class TournamentRow(@PrimaryKey val id: String, val payload: String)
@Entity(tableName = "fixture") data class FixtureRow(@PrimaryKey val id: String, val payload: String)
/** Private table: never joined into broadcast or registry exports. */
@Entity(tableName = "availability", primaryKeys = ["fixture_id", "player_id"], indices = [Index("player_id")])
data class AvailabilityRow(@ColumnInfo(name = "fixture_id") val fixtureId: String,
    @ColumnInfo(name = "player_id") val playerId: String, val choice: String)

@Dao interface ScheduleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun saveLocation(row: LocationRow)
    @Query("SELECT * FROM location ORDER BY id") fun locations(): List<LocationRow>
    @Query("DELETE FROM location WHERE id = :id") fun deleteLocation(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun saveTournament(row: TournamentRow)
    @Query("SELECT * FROM tournament ORDER BY id") fun tournaments(): List<TournamentRow>
    @Query("DELETE FROM tournament WHERE id = :id") fun deleteTournament(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun saveFixture(row: FixtureRow)
    @Query("SELECT * FROM fixture ORDER BY id") fun fixtures(): List<FixtureRow>
    @Query("DELETE FROM fixture WHERE id = :id") fun deleteFixture(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun saveAvailability(row: AvailabilityRow)
    @Query("SELECT * FROM availability WHERE fixture_id = :fixtureId ORDER BY player_id") fun availability(fixtureId: String): List<AvailabilityRow>
    @Query("DELETE FROM availability WHERE fixture_id = :fixtureId") fun deleteAvailability(fixtureId: String)
    @Query("DELETE FROM availability WHERE player_id = :playerId") fun deletePlayerAvailability(playerId: String)
}
