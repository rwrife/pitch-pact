package com.infinityball.pitchpact.domain

import com.infinityball.pitchpact.dto.ConsensusDecision
import kotlin.test.*

class ConsensusContestedTest {
    private fun goal(id: String, side: Side, jersey: Int = 9) =
        LedgerEvent.Event(id, side, 1_000_000L, EventPayload.Goal(side, jersey))

    private fun sub(changeId: String, eventId: String, side: Side, match: String = "m1") =
        SubmissionRequest(ScoreChange(changeId, match, side, eventId, ScoreChangeAction.ADD), goal(eventId, side))

    @Test fun bothSidesSubmitSimultaneously_staysOfficialNeutralUntilAccepted() {
        var book = emptyList<ConsensusDecision>()
        book = ConsensusBook.submit(book, sub("c-home", "g-home", Side.HOME), Side.HOME, 100)
        book = ConsensusBook.submit(book, sub("c-away", "g-away", Side.AWAY), Side.AWAY, 101)
        assertEquals(2, book.size)
        assertTrue(book.all { it.state == ConsensusState.PENDING })
        var p = ConsensusBook.project(book, emptySet())
        assertEquals("0-0", p.officialScore.render())
        assertEquals(2, p.pending.size)

        // Opposing accepts home goal -> 1-0 official, away goal still pending
        book = ConsensusBook.review(book, "c-home", Side.AWAY, ConsensusAction.ACCEPT, 200)
        p = ConsensusBook.project(book, emptySet())
        assertEquals("1-0", p.officialScore.render())
        assertEquals(1, p.pending.size)

        // Opposing rejects away goal -> official stays 1-0; rejected stays in book
        book = ConsensusBook.review(book, "c-away", Side.HOME, ConsensusAction.REJECT, 201)
        p = ConsensusBook.project(book, emptySet())
        assertEquals("1-0", p.officialScore.render())
        assertEquals(0, p.pending.size)
        assertEquals(2, p.ledger.size) // both events kept forever
    }

    @Test fun duplicateIdempotentSubmitsAndReplays() {
        var book = emptyList<ConsensusDecision>()
        book = ConsensusBook.submit(book, sub("c1", "g1", Side.HOME), Side.HOME, 100)
        val same = ConsensusBook.submit(book, sub("c1", "g1", Side.HOME), Side.HOME, 105)
        assertSame(book, same)
        // Conflicting replay (same changeId, different eventId) fails loudly
        assertFailsWith<IllegalArgumentException> {
            ConsensusBook.submit(book, sub("c1", "g2", Side.HOME), Side.HOME, 110)
        }
        // Submitter accepts own change is forbidden
        assertFailsWith<IllegalArgumentException> {
            ConsensusBook.review(book, "c1", Side.HOME, ConsensusAction.ACCEPT, 120)
        }
        // Opposing review replay is idempotent
        book = ConsensusBook.review(book, "c1", Side.AWAY, ConsensusAction.ACCEPT, 120)
        val replay = ConsensusBook.review(book, "c1", Side.AWAY, ConsensusAction.ACCEPT, 125)
        assertSame(book, replay)
    }

    @Test fun reSubmitAfterReject_producesNewRecordAndOfficialReflects() {
        var book = emptyList<ConsensusDecision>()
        book = ConsensusBook.submit(book, sub("c1", "g1", Side.HOME), Side.HOME, 100)
        book = ConsensusBook.review(book, "c1", Side.AWAY, ConsensusAction.REJECT, 110)
        assertEquals("0-0", ConsensusBook.project(book, emptySet()).officialScore.render())
        // Attempting to accept a rejected change fails
        assertFailsWith<IllegalArgumentException> {
            ConsensusBook.review(book, "c1", Side.AWAY, ConsensusAction.ACCEPT, 120)
        }
        // Sideline resubmits with a distinct changeId
        book = ConsensusBook.submit(book, sub("c2", "g1", Side.HOME), Side.HOME, 130)
        book = ConsensusBook.review(book, "c2", Side.AWAY, ConsensusAction.ACCEPT, 140)
        val p = ConsensusBook.project(book, emptySet())
        assertEquals("1-0", p.officialScore.render())
        assertEquals(2, book.size)
        assertEquals(ConsensusState.REJECTED, book[0].state)
        assertEquals(ConsensusState.ACCEPTED, book[1].state)
    }

    @Test fun withdrawAfterAcceptIsRefused_withdrawWhilePendingWorks() {
        var book = emptyList<ConsensusDecision>()
        book = ConsensusBook.submit(book, sub("c1", "g1", Side.HOME), Side.HOME, 100)
        book = ConsensusBook.submit(book, sub("c2", "g2", Side.AWAY), Side.AWAY, 101)
        // Submitter withdraws pending change
        book = ConsensusBook.review(book, "c1", Side.HOME, ConsensusAction.WITHDRAW, 105)
        assertEquals(ConsensusState.WITHDRAWN, book[0].state)
        // Opposing accept c2 -> becomes official
        book = ConsensusBook.review(book, "c2", Side.HOME, ConsensusAction.ACCEPT, 110)
        // Withdraw after accept refused
        assertFailsWith<IllegalArgumentException> {
            ConsensusBook.review(book, "c2", Side.AWAY, ConsensusAction.WITHDRAW, 120)
        }
        assertEquals("0-1", ConsensusBook.project(book, emptySet()).officialScore.render())
    }

    @Test fun finalWhistleConsensusOfficialGate() {
        var book = emptyList<ConsensusDecision>()
        book = ConsensusBook.submit(book, sub("c1", "g1", Side.HOME), Side.HOME, 100)
        var whistles = setOf<Side>()
        whistles = ConsensusBook.whistle(whistles, Side.HOME)
        whistles = ConsensusBook.whistle(whistles, Side.AWAY)
        // Both whistles blown, BUT change c1 is still pending -> official MUST remain false
        var p = ConsensusBook.project(book, whistles)
        assertFalse(p.official, "Match with pending score changes cannot be marked official")
        // Resolve c1
        book = ConsensusBook.review(book, "c1", Side.AWAY, ConsensusAction.ACCEPT, 110)
        p = ConsensusBook.project(book, whistles)
        assertTrue(p.official, "Both whistles + 0 pending changes = official match")
    }

    @Test fun adversarialContestedHalf_officialScoreAlwaysEqualsAcceptedRecompute() {
        var book = emptyList<ConsensusDecision>()
        var whistles = setOf<Side>()
        // Play-by-play sequence over a 45-minute half
        // Minute 4: Home scores
        book = ConsensusBook.submit(book, sub("m4-g1", "e-1", Side.HOME), Side.HOME, 1_000)
        assertScore(book, whistles, "0-0")
        // Minute 5: Away accepts -> 1-0
        book = ConsensusBook.review(book, "m4-g1", Side.AWAY, ConsensusAction.ACCEPT, 1_100)
        assertScore(book, whistles, "1-0")

        // Minute 12: Away scores, Home rejects (handball claim)
        book = ConsensusBook.submit(book, sub("m12-g2", "e-2", Side.AWAY), Side.AWAY, 2_000)
        book = ConsensusBook.review(book, "m12-g2", Side.HOME, ConsensusAction.REJECT, 2_100)
        assertScore(book, whistles, "1-0") // rejected away goal never surfaces

        // Minute 22: Home scores own goal, logs as Home side event
        val ownGoal = LedgerEvent.Event("e-3", Side.HOME, 3_000L, EventPayload.Goal(Side.HOME, 4, goalType = GoalType.OWN_GOAL))
        book = ConsensusBook.submit(book, SubmissionRequest(ScoreChange("m22-og", "m1", Side.HOME, "e-3", ScoreChangeAction.ADD), ownGoal), Side.HOME, 3_000)
        assertScore(book, whistles, "1-0")
        book = ConsensusBook.review(book, "m22-og", Side.AWAY, ConsensusAction.ACCEPT, 3_100)
        // Own goal gives 1 to away!
        assertScore(book, whistles, "1-1")

        // Minute 35: Away scores legitimate goal, accepted by Home
        book = ConsensusBook.submit(book, sub("m35-g4", "e-4", Side.AWAY), Side.AWAY, 4_000)
        book = ConsensusBook.review(book, "m35-g4", Side.HOME, ConsensusAction.ACCEPT, 4_100)
        assertScore(book, whistles, "1-2")

        // Minute 44: Home phantom goal submitted, submitter withdraws before review
        book = ConsensusBook.submit(book, sub("m44-g5", "e-5", Side.HOME), Side.HOME, 5_000)
        book = ConsensusBook.review(book, "m44-g5", Side.HOME, ConsensusAction.WITHDRAW, 5_050)
        assertScore(book, whistles, "1-2")

        // Minute 45: Full time whistle
        whistles = ConsensusBook.whistle(whistles, Side.HOME)
        assertFalse(ConsensusBook.project(book, whistles).official)
        whistles = ConsensusBook.whistle(whistles, Side.AWAY)
        val finalProject = ConsensusBook.project(book, whistles)
        assertTrue(finalProject.official)
        assertEquals("1-2", finalProject.officialScore.render())
        // Record keeping: all 5 events + their 5 audit decisions exist forever
        assertEquals(5, finalProject.ledger.size)
        assertEquals(5, book.size)
    }

    private fun assertScore(book: List<ConsensusDecision>, whistles: Set<Side>, expected: String) {
        val p = ConsensusBook.project(book, whistles)
        assertEquals(expected, p.officialScore.render())
    }
}
