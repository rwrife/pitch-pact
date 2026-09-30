package com.infinityball.pitchpact.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsensusStateMachineTest {

    private fun record(id: String = "ch1", submittedBy: Side = Side.HOME) = ConsensusRecord(
        ScoreChange(
            changeId = id,
            matchId = "m1",
            submittedBy = submittedBy,
            ledgerEventId = "g1",
            action = ScoreChangeAction.ADD,
        ),
    )

    @Test
    fun opposingAcceptMakesOfficial() {
        val r = record()
        val t = r.apply(Side.AWAY, ConsensusAction.ACCEPT)
        assertTrue(t.allowed)
        assertEquals(ConsensusState.ACCEPTED, r.state)
    }

    @Test
    fun opposingRejectLeavesOfficialUnchanged() {
        val r = record()
        assertTrue(r.apply(Side.AWAY, ConsensusAction.REJECT).allowed)
        assertEquals(ConsensusState.REJECTED, r.state)
    }

    @Test
    fun submitterCannotAcceptOwnChange() {
        val r = record()
        val t = r.apply(Side.HOME, ConsensusAction.ACCEPT)
        assertFalse(t.allowed, "auto-accept by submitter is forbidden by contract")
        assertEquals(ConsensusState.PENDING, r.state)
    }

    @Test
    fun submitterWithdrawsWhilePending() {
        val r = record()
        assertTrue(r.apply(Side.HOME, ConsensusAction.WITHDRAW).allowed)
        assertEquals(ConsensusState.WITHDRAWN, r.state)
    }

    @Test
    fun opposingSideCannotWithdraw() {
        val r = record()
        assertFalse(r.apply(Side.AWAY, ConsensusAction.WITHDRAW).allowed)
        assertEquals(ConsensusState.PENDING, r.state)
    }

    @Test
    fun terminalStatesAreImmutable_underEveryActorAndAction() {
        for (start in listOf(ConsensusState.ACCEPTED, ConsensusState.REJECTED, ConsensusState.WITHDRAWN)) {
            val r = record()
            // fast-forward to the start state
            when (start) {
                ConsensusState.ACCEPTED -> r.apply(Side.AWAY, ConsensusAction.ACCEPT)
                ConsensusState.REJECTED -> r.apply(Side.AWAY, ConsensusAction.REJECT)
                ConsensusState.WITHDRAWN -> r.apply(Side.HOME, ConsensusAction.WITHDRAW)
                else -> Unit
            }
            for (actor in Side.entries) {
                for (action in ConsensusAction.entries) {
                    val t = r.apply(actor, action)
                    assertFalse(t.allowed, "$start + $actor/$action must be rejected")
                    assertEquals(start, r.state, "$start + $actor/$action must not move state")
                }
            }
        }
    }

    @Test
    fun resubmitAfterRejectIsANewRecord_notInPlaceReactivation() {
        val rejected = record("ch1")
        rejected.apply(Side.AWAY, ConsensusAction.REJECT)
        // The rejected record never reopens...
        assertFalse(rejected.apply(Side.AWAY, ConsensusAction.ACCEPT).allowed)
        // ...a resubmission is a NEW changeId in PENDING state.
        val fresh = record("ch2")
        assertEquals(ConsensusState.PENDING, fresh.state)
    }

    @Test
    fun withdrawAfterAcceptIsRefused_officialStands() {
        val r = record()
        r.apply(Side.AWAY, ConsensusAction.ACCEPT)
        assertFalse(r.apply(Side.HOME, ConsensusAction.WITHDRAW).allowed)
        assertEquals(ConsensusState.ACCEPTED, r.state)
    }
}

class ConsensusScoreboardTest {

    private fun goal(id: String, side: Side) = LedgerEvent.Event(
        eventId = id, side = side, recordedAtEpochMillis = 0,
        payload = EventPayload.Goal(side = side, jerseyNumber = 9),
    )

    @Test
    fun officialScoreCountsAcceptedOnly() {
        val ledger = MatchLedger()
        ledger.append(goal("g1", Side.HOME))
        ledger.append(goal("g2", Side.HOME))
        ledger.append(goal("g3", Side.AWAY))

        val accepted = ConsensusRecord(
            ScoreChange("c1", "m1", Side.HOME, "g1", ScoreChangeAction.ADD),
        ).apply { apply(Side.AWAY, ConsensusAction.ACCEPT) }
        val pending = ConsensusRecord(
            ScoreChange("c2", "m1", Side.HOME, "g2", ScoreChangeAction.ADD),
        )
        val rejectedAway = ConsensusRecord(
            ScoreChange("c3", "m1", Side.AWAY, "g3", ScoreChangeAction.ADD),
        ).apply { apply(Side.HOME, ConsensusAction.REJECT) }

        val score = ConsensusScoring.officialScore(
            ledger,
            listOf(accepted, pending, rejectedAway),
        )
        assertEquals("1-0", score.render())
    }

    @Test
    fun rejectedChangeStillKeepsLedgerEvent() {
        val ledger = MatchLedger()
        ledger.append(goal("g1", Side.AWAY))
        val rejected = ConsensusRecord(
            ScoreChange("c1", "m1", Side.AWAY, "g1", ScoreChangeAction.ADD),
        ).apply { apply(Side.HOME, ConsensusAction.REJECT) }
        ConsensusScoring.officialScore(ledger, listOf(rejected))
        // Record-keeping survives rejection: ledger rows never disappear.
        assertEquals(1, ledger.events.size)
        assertEquals(1, ledger.effectiveEvents().size)
    }
}
