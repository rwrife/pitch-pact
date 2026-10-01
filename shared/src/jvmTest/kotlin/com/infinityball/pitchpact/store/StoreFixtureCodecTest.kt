package com.infinityball.pitchpact.store

import com.infinityball.pitchpact.M2Facade
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shared fixture round-trip contract: the canonical M2 fixture file is the
 * single artifact BOTH platform stores must round-trip unchanged (iOS GRDB
 * store test and Android Room store test load the same file).
 */
class StoreFixtureCodecTest {

    private fun fixtureText(): String {
        // Read the checked-in fixture from the repo root (gradle test wd),
        // tolerating module-level working directories.
        for (base in listOf(".", "..", "../..")) {
            val f = java.io.File("$base/fixtures/m2-teams.json")
            if (f.isFile) return f.readText()
        }
        error("fixtures/m2-teams.json not found from ${System.getProperty("user.dir")}")
    }

    @Test
    fun canonicalFixtureRoundTripsThroughSharedCodec() {
        val json = fixtureText()
        val out = M2Facade.fixtureRoundTrip(json)
        val (teams, players) = StoreFixtureCodec.decode(out)
        assertEquals(2, teams.size)
        assertTrue(teams.any { it.isArchived }, "fixture must cover the archived case")
        assertEquals(4, players.size)
        // Re-encoding is stable (idempotent round trip).
        assertEquals(out, M2Facade.fixtureRoundTrip(out))
    }

    @Test
    fun sharedValidationWordingIsSurfacedThroughFacade() {
        val roster = """{"teams":[],"players":[{"id":"a","teamId":"t1","name":"Bo","jerseyNumber":9,"createdAtEpochMillis":0}]}"""
        val clash = """{"id":"b","teamId":"t1","name":"Cy","jerseyNumber":9,"createdAtEpochMillis":0}"""
        val msg = M2Facade.playerValidationError(roster, clash)
        assertNotNull(msg)
        assertTrue(msg.contains("Bo"))
        assertNull(
            M2Facade.playerValidationError(roster, clash.replace("\"jerseyNumber\":9", "\"jerseyNumber\":11")),
        )
    }
}
