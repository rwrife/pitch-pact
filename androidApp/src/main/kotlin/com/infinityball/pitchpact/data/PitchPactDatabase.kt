package com.infinityball.pitchpact.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.infinityball.pitchpact.store.TeamStore

@Database(
    entities = [TeamRow::class, PlayerRow::class, UniformRow::class, GuardianRow::class,
        LocationRow::class, TournamentRow::class, FixtureRow::class, AvailabilityRow::class, MatchDayRow::class],
    version = 3,
    exportSchema = true,
)
abstract class PitchPactDatabase : RoomDatabase() {
    abstract fun matchDayDao(): MatchDayDao
    abstract fun teamDao(): TeamDao
    abstract fun scheduleDao(): ScheduleDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `match_day` (`id` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`))")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("location", "tournament", "fixture"))
                    db.execSQL("CREATE TABLE IF NOT EXISTS `$table` (`id` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `availability` (`fixture_id` TEXT NOT NULL, `player_id` TEXT NOT NULL, `choice` TEXT NOT NULL, PRIMARY KEY(`fixture_id`, `player_id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_availability_player_id` ON `availability` (`player_id`)")
            }
        }
        fun build(context: Context): PitchPactDatabase =
            Room.databaseBuilder(context, PitchPactDatabase::class.java, RoomTeamStore.DB_NAME)
                // Single-threaded-by-contract store: the TeamStore contract
                // drives all access from the UI thread (see shared/TeamStore).
                // Reads move to background dispatchers only with M3+ sync.
                .allowMainThreadQueries()
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
