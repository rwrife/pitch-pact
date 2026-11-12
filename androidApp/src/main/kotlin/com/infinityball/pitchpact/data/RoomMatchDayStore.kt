package com.infinityball.pitchpact.data

import androidx.room.*
import com.infinityball.pitchpact.domain.*

@Entity(tableName = "match_day") data class MatchDayRow(@PrimaryKey val id: String, val payload: String)
@Dao interface MatchDayDao {
    @Query("SELECT payload FROM match_day WHERE id = :id") fun load(id: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun save(row: MatchDayRow)
    @Query("DELETE FROM match_day WHERE id = :id") fun delete(id: String)
    @Query("SELECT * FROM match_day ORDER BY id") fun all(): List<MatchDayRow>
    @Query("DELETE FROM match_day") fun clear()
}
class RoomMatchDayStore(private val db: PitchPactDatabase) {
    fun load(id: String): MatchDayState = db.matchDayDao().load(id)?.let(MatchDayCodec::decode) ?: MatchDayState(id)
    fun save(state: MatchDayState) {
        val value = MatchDayCodec.encode(state)
        MatchDayCodec.decode(value)
        db.runInTransaction { db.matchDayDao().save(MatchDayRow(state.matchId, value)) }
    }
    fun capture(id: String, event: LedgerEvent) = load(id).capture(event).also(::save)
}
