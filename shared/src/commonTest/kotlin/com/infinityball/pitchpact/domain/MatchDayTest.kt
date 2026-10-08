package com.infinityball.pitchpact.domain
import kotlin.test.*
import kotlin.random.Random

class MatchDayTest {
    private fun sample(t: Long, boot: String = "a", epoch: Long = 10000 + t) = ClockSample(t, epoch, boot)
    private fun goal(id: String = "g", side: Side = Side.HOME) = LedgerEvent.Event(id, side, 0, EventPayload.Goal(side, 9))
    @Test fun clockTableAndFirstHalfStoppage() {
        var clock = MatchClock()
        for (expected in listOf(MatchPhase.FIRST_HALF, MatchPhase.HALFTIME, MatchPhase.SECOND_HALF, MatchPhase.STOPPAGE, MatchPhase.FULL_TIME)) {
            clock = clock.advance(sample(0)); assertEquals(expected, clock.phase)
        }
        clock = MatchClock().advance(sample(0)).stoppage(sample(45000))
        assertEquals(45000, clock.elapsed(sample(45000)))
        assertEquals(MatchPhase.HALFTIME, clock.advance(sample(50000)).phase)
        clock = clock.advance(sample(50000)).advance(sample(60000)).advance(sample(61000)).advance(sample(62000))
        assertEquals(MatchPhase.FULL_TIME, clock.phase)
    }
    @Test fun restartUsesBootCorrelationAndPreservesElapsed() {
        val clock = MatchClock().advance(sample(100)).checkpoint(sample(1100))
        assertEquals(2000, clock.elapsed(sample(20, "b", 12100)))
        assertFailsWith<IllegalArgumentException> { clock.elapsed(sample(20, "b", 0)) }
        assertEquals(clock, MatchDayCodec.decode(MatchDayCodec.encode(MatchDayState("m", clock = clock))).clock)
    }
    @Test fun seededReplayAndConflicts() {
        val random = Random(4)
        val events = (0..300).map { goal("g$it", if (random.nextBoolean()) Side.HOME else Side.AWAY) }
        val ledger = MatchLedger(); ledger.replayAll(events)
        repeat(10) { assertEquals(0, ledger.replayAll(events.shuffled(random))) }
        assertEquals(events, ledger.events)
        assertFailsWith<IllegalArgumentException> { ledger.append(goal("g0", if (events[0].side == Side.HOME) Side.AWAY else Side.HOME)) }
        assertFailsWith<IllegalArgumentException> { ledger.append(goal("")) }
        assertFailsWith<IllegalArgumentException> { ledger.append(goal().copy(recordedAtEpochMillis = -1)) }
        assertFailsWith<IllegalArgumentException> { ledger.append(goal().copy(payload = EventPayload.Goal(Side.AWAY, -2))) }
    }
    @Test fun queueUndoAndOfficialCorrectionIsolation() {
        val original = goal()
        var state = MatchDayState("m").capture(original)
        assertEquals(emptyList(), state.undoTail().events)
        state = state.acknowledge(listOf(original))
        assertFailsWith<IllegalArgumentException> { state.undoTail() }
        val ledger = MatchLedger().apply { append(original) }
        val record = ConsensusRecord(ScoreChange("s", "m", Side.HOME, "g", ScoreChangeAction.ADD))
        record.apply(Side.AWAY, ConsensusAction.ACCEPT)
        ledger.append(LedgerEvent.Correction("c", Side.HOME, 1, "g", original.copy(eventId = "r", payload = EventPayload.Goal(Side.HOME, 9, goalType = GoalType.OWN_GOAL))))
        assertEquals(OfficialScore(1, 0), ConsensusScoring.officialScore(ledger, listOf(record)))
    }
    @Test fun acceptedOwnGoalAndCorrectionRequireOpposingSide() {
        val own = goal().copy(payload = EventPayload.Goal(Side.HOME, 9, goalType = GoalType.OWN_GOAL))
        val add = ScoreChange("add", "m", Side.HOME, "g", ScoreChangeAction.ADD)
        var state = MatchDayState("m").capture(own).copy(decisions = listOf(StoredScoreDecision(add, ConsensusState.ACCEPTED, Side.AWAY)))
        assertEquals(OfficialScore(0, 1), state.officialScore())
        val correction = LedgerEvent.Correction("c", Side.HOME, 1, "g", goal("r"))
        state = state.capture(correction)
        assertEquals(OfficialScore(0, 1), state.officialScore())
        val corrected = StoredScoreDecision(ScoreChange("correct", "m", Side.HOME, "c", ScoreChangeAction.CORRECT), ConsensusState.ACCEPTED, Side.AWAY)
        assertEquals(OfficialScore(1, 0), state.copy(decisions = state.decisions + corrected).officialScore())
        assertFailsWith<IllegalArgumentException> { state.copy(decisions = listOf(StoredScoreDecision(add, ConsensusState.ACCEPTED, Side.HOME))).officialScore() }
        assertFailsWith<IllegalArgumentException> { MatchDayState("m").capture(own).prepareSync().undoTail() }
    }
    @Test fun acceptedCorrectionIgnoresDecisionOrderAndDuplicates() {
        val original = goal()
        val correction = LedgerEvent.Correction("c", Side.HOME, 1, "g",
            goal("r").copy(payload = EventPayload.Goal(Side.HOME, 9, goalType = GoalType.OWN_GOAL)))
        val ledger = MatchLedger().apply { replayAll(listOf(original, correction)) }
        fun accepted(id: String, event: String, action: ScoreChangeAction) =
            ConsensusRecord(ScoreChange(id, "m", Side.HOME, event, action)).apply { apply(Side.AWAY, ConsensusAction.ACCEPT) }
        val add = accepted("add", "g", ScoreChangeAction.ADD)
        val correct = accepted("correct", "c", ScoreChangeAction.CORRECT)
        for (records in listOf(listOf(add, correct), listOf(correct, add), listOf(correct, add, correct, add))) {
            assertEquals(OfficialScore(0, 1), ConsensusScoring.officialScore(ledger, records))
            assertEquals(listOf(original, correction), ledger.events)
        }
    }
    @Test fun acceptedRemovalIgnoresDecisionOrder() {
        val ledger = MatchLedger().apply { append(goal()) }
        val add = ConsensusRecord(ScoreChange("add", "m", Side.HOME, "g", ScoreChangeAction.ADD)).apply { apply(Side.AWAY, ConsensusAction.ACCEPT) }
        val remove = ConsensusRecord(ScoreChange("remove", "m", Side.HOME, "g", ScoreChangeAction.REMOVE)).apply { apply(Side.AWAY, ConsensusAction.ACCEPT) }
        for (records in listOf(listOf(add, remove), listOf(remove, add), listOf(add, remove, add))) {
            assertEquals(OfficialScore(0, 0), ConsensusScoring.officialScore(ledger, records))
        }
    }
    @Test fun acceptedChainTraversesPendingAndRejectedIntermediate() {
        val original = goal()
        val first = LedgerEvent.Correction("c1", Side.HOME, 1, "g", goal("r1"))
        val last = LedgerEvent.Correction("c2", Side.HOME, 2, "r1",
            goal("r2").copy(payload = EventPayload.Goal(Side.HOME, 9, goalType = GoalType.OWN_GOAL)))
        val ledger = MatchLedger().apply { replayAll(listOf(original, first, last)) }
        fun record(id: String, event: String, action: ScoreChangeAction) = ConsensusRecord(ScoreChange(id, "m", Side.HOME, event, action))
        val add = record("add", "g", ScoreChangeAction.ADD).apply { apply(Side.AWAY, ConsensusAction.ACCEPT) }
        val correct = record("last", "c2", ScoreChangeAction.CORRECT).apply { apply(Side.AWAY, ConsensusAction.ACCEPT) }
        for (rejected in listOf(false, true)) {
            val middle = record("middle", "c1", ScoreChangeAction.CORRECT)
            if (rejected) middle.apply(Side.AWAY, ConsensusAction.REJECT)
            val all = listOf(add, middle, correct)
            for (a in all.indices) for (b in all.indices) if (a != b) {
                val records = listOf(all[a], all[b], all[3 - a - b])
                assertEquals(OfficialScore(0, 1), ConsensusScoring.officialScore(ledger, records))
                assertEquals(OfficialScore(0, 1), ConsensusScoring.officialScore(ledger, records + records))
            }
            assertEquals(OfficialScore(1, 0), ConsensusScoring.officialScore(ledger, listOf(add, middle)))
            assertEquals(OfficialScore(0, 0), ConsensusScoring.officialScore(ledger, listOf(middle, correct)))
            assertEquals(listOf(original, first, last), ledger.events)
        }
    }
    @Test fun captureCategoriesValidationAndVerifiedReceipt() {
        val payloads = listOf<EventPayload>(EventPayload.PenaltyMiss(Side.AWAY, 4), EventPayload.Substitution(Side.AWAY, 4, 5),
            EventPayload.Card(Side.AWAY, 4, CardColor.YELLOW), EventPayload.Card(Side.AWAY, 4, CardColor.RED)) +
            ExtraKind.entries.map { EventPayload.Extra(Side.AWAY, it) }
        var state = MatchDayState("m")
        payloads.forEachIndexed { index, payload -> state = state.capture(LedgerEvent.Event("e$index", Side.AWAY, 0, payload)) }
        assertEquals(state, MatchDayCodec.decode(MatchDayCodec.encode(state)))
        assertFailsWith<IllegalArgumentException> { state.acknowledge(listOf(goal())) }
        assertFailsWith<IllegalArgumentException> { state.copy(enabledExtras = emptySet()).capture(LedgerEvent.Event("disabled", Side.AWAY, 0, EventPayload.Extra(Side.AWAY, ExtraKind.CORNER))) }
        assertEquals(emptyList(), state.acknowledge(state.events).queuedIds)
    }
    @Test fun invalidClockAndNativeInputAreRejected() {
        assertFailsWith<IllegalArgumentException> { ClockSample(-1, 0, "boot") }
        assertFailsWith<IllegalArgumentException> { ClockSample(0, -1, "boot") }
        assertFailsWith<IllegalArgumentException> { ClockSample(0, 0, "") }
        assertFailsWith<IllegalArgumentException> { MatchClock(elapsedMillis = -1) }
        val rules = com.infinityball.pitchpact.MatchDayFacade()
        assertFailsWith<IllegalArgumentException> { rules.captureInput(rules.initial("m"), "g", "HOME", 0, "OPEN_PLAY", "9", "-1", "10") }
        assertFailsWith<IllegalArgumentException> { rules.captureInput(rules.initial("m"), "g", "HOME", 0, "OPEN_PLAY", "-9", "", "10") }
    }
}
