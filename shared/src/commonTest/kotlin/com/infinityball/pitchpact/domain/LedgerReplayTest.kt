package com.infinityball.pitchpact.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LedgerReplayTest {

    private fun goal(id: String, side: Side, jersey: Int, minute: Long = 0) =
        LedgerEvent.Event(
            eventId = id,
            side = side,
            recordedAtEpochMillis = minute,
            payload = EventPayload.Goal(side = side, jerseyNumber = jersey),
        )

    @Test
    fun appendIsIdempotentByEventId() {
        val ledger = MatchLedger()
        val e = goal("g1", Side.HOME, 9)
        assertTrue(ledger.append(e))
        assertFalse(ledger.append(e), "replay of same eventId must be a no-op")
        assertEquals(1, ledger.events.size)
    }

    @Test
    fun replayAllSkipsDuplicatesAndPreservesOrder() {
        val ledger = MatchLedger()
        val events = listOf(
            goal("g1", Side.HOME, 9),
            goal("g2", Side.AWAY, 11),
            goal("g3", Side.HOME, 10),
        )
        assertEquals(3, ledger.replayAll(events))
        assertEquals(0, ledger.replayAll(events), "full-queue replay appends nothing")
        assertEquals(listOf("g1", "g2", "g3"), ledger.events.map { it.eventId })
    }

    @Test
    fun interleavedReplayYieldsIdenticalProjection() {
        val events = listOf(
            goal("g1", Side.HOME, 9),
            goal("g2", Side.AWAY, 11),
            goal("g3", Side.HOME, 10),
        )
        val a = MatchLedger().apply { replayAll(events) }
        val b = MatchLedger().apply {
            replayAll(events)
            replayAll(events.reversed()) // duplicate burst
        }
        assertEquals(a.events, b.events)
        assertEquals(a.effectiveEvents(), b.effectiveEvents())
    }

    @Test
    fun correctionReplacesInScoreboardButKeepsLedger() {
        val ledger = MatchLedger()
        val wrong = goal("g1", Side.HOME, 9)
        val right = goal("g1c", Side.HOME, 10)
        ledger.append(wrong)
        ledger.append(
            LedgerEvent.Correction(
                eventId = "c1",
                side = Side.HOME,
                recordedAtEpochMillis = 5,
                correctsEventId = "g1",
                replacement = right,
            ),
        )
        // Full ledger keeps both rows forever (record-keeping).
        assertEquals(2, ledger.events.size)
        // Scoreboard view: the corrected goal is replaced, not duplicated.
        val eff = ledger.effectiveEvents().filterIsInstance<LedgerEvent.Event>()
        assertEquals(listOf("g1c"), eff.map { it.eventId })
    }
}
