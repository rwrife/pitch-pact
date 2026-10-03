package com.infinityball.pitchpact.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.infinityball.pitchpact.store.TeamStore

@Database(
    entities = [TeamRow::class, PlayerRow::class, UniformRow::class, GuardianRow::class],
    // KSP evaluates annotation arguments at compile time and cannot resolve
    // cross-file companion constants; 1 is the v1 schema (matches the shared
    // TeamStore contract and GRDB's first migration on the iOS side).
    version = 1,
    exportSchema = true,
)
abstract class PitchPactDatabase : RoomDatabase() {
    abstract fun teamDao(): TeamDao

    companion object {
        fun build(context: Context): PitchPactDatabase =
            Room.databaseBuilder(context, PitchPactDatabase::class.java, RoomTeamStore.DB_NAME)
                // Single-threaded-by-contract store: the TeamStore contract
                // drives all access from the UI thread (see shared/TeamStore).
                // Reads move to background dispatchers only with M3+ sync.
                .allowMainThreadQueries()
                .build()
    }
}
