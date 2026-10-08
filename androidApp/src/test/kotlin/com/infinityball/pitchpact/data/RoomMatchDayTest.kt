package com.infinityball.pitchpact.data
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.infinityball.pitchpact.MatchDayFacade
import com.infinityball.pitchpact.domain.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(AndroidJUnit4::class) @Config(sdk = [34])
class RoomMatchDayTest {
    @Test fun captureRestartQueueAndInvalidWrite() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "match-test.sqlite"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, PitchPactDatabase::class.java, name).allowMainThreadQueries().build()
        var db = open()
        val rules = MatchDayFacade()
        val next = rules.capture(rules.initial("m"), "g", "HOME", 100, "OWN_GOAL", 9, -1, 10)
        val store = RoomMatchDayStore(db)
        store.save(MatchDayCodec.decode(next)); db.close()
        db = open()
        val restored = RoomMatchDayStore(db).load("m")
        assertEquals(listOf("g"), restored.queuedIds)
        assertEquals(OfficialScore(0, 1), restored.pendingScore())
        assertFailsWith<IllegalArgumentException> { RoomMatchDayStore(db).capture("m", (restored.events.single() as LedgerEvent.Event).copy(recordedAtEpochMillis = 200)) }
        assertEquals(restored, RoomMatchDayStore(db).load("m"))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_match BEFORE INSERT ON match_day BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        assertFails { RoomMatchDayStore(db).save(restored.copy(queuedIds = emptyList())) }
        assertEquals(restored, RoomMatchDayStore(db).load("m"))
        db.close(); context.deleteDatabase(name)
    }
    @Test fun fullOfflineGameRestartServerDuplicateReplayAndReceipt() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "full-match-test.sqlite"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, PitchPactDatabase::class.java, name).allowMainThreadQueries().build()
        var db = open()
        val directory = java.nio.file.Files.createTempDirectory("match-server")
        try {
            val rules = MatchDayFacade()
            val store = RoomMatchDayStore(db)
            var json = rules.initial("full-match")
            val kinds = listOf("OPEN_PLAY", "PENALTY", "OWN_GOAL", "SET_PIECE", "PENALTY_MISS", "SUBSTITUTION", "YELLOW", "RED", "CORNER", "OFFSIDE", "SHOT_ON_TARGET", "SAVE", "INJURY_STOPPAGE")
            var time = 0L
            fun advance(stoppage: Boolean = false) {
                time += 1000
                json = rules.advance(json, time, 10000 + time, "boot", stoppage, "clock$time", "HOME")
                store.save(MatchDayCodec.decode(json))
            }
            advance()
            kinds.forEachIndexed { index, kind ->
                json = rules.capture(json, "event$index", if (index % 2 == 0) "HOME" else "AWAY", 10000 + time, kind, 9, -1, 10)
                store.save(MatchDayCodec.decode(json))
            }
            advance(true); advance(); advance(); advance(true); advance()
            val offline = store.load("full-match")
            assertEquals(MatchPhase.FULL_TIME, offline.clock.phase)
            assertEquals(19, offline.events.size)
            assertEquals(OfficialScore(0, 0), offline.officialScore())
            db.close(); db = open()
            val restarted = RoomMatchDayStore(db)
            assertEquals(offline, restarted.load("full-match"))
            json = rules.prepareSync(MatchDayCodec.encode(restarted.load("full-match")))
            restarted.save(MatchDayCodec.decode(json))
            val requestJSON = rules.request(json)
            val request = MatchDayCodec.json.decodeFromString(MatchSyncRequest.serializer(), requestJSON)
            val repository = com.infinityball.pitchpact.server.MatchRepository(directory)
            val receipt = repository.receive(request)
            val stored = directory.resolve("full-match.json").toFile().readText()
            assertEquals(receipt, com.infinityball.pitchpact.server.MatchRepository(directory).receive(request))
            assertEquals(stored, directory.resolve("full-match.json").toFile().readText())
            val receiptJSON = MatchDayCodec.json.encodeToString(MatchSyncReceipt.serializer(), receipt)
            restarted.save(MatchDayCodec.decode(rules.receipt(json, requestJSON, receiptJSON)))
            db.close(); db = open()
            assertTrue(RoomMatchDayStore(db).load("full-match").queuedIds.isEmpty())
            assertEquals(offline.events, RoomMatchDayStore(db).load("full-match").events)
        } finally {
            db.close(); context.deleteDatabase(name); directory.toFile().deleteRecursively()
        }
    }
}
