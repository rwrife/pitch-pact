package com.infinityball.pitchpact

import com.infinityball.pitchpact.domain.*
import com.infinityball.pitchpact.dto.*
import kotlin.test.*

/**
 * The UI bridge's contract: wording and side mapping come from `shared`, the
 * official score never absorbs a pending change, and the record ledger keeps
 * every outcome. One runnable check per surface the apps render.
 */
class ConsensusFacadeTest {
    private val json = PitchPactJson.codec
    private val facade = ConsensusFacade()

    private fun goal(id: String, side: Side) = LedgerEvent.Event(id, side, 1_000, EventPayload.Goal(side, 9))
    private fun decision(id: String, side: Side, state: ConsensusState) = ConsensusDecision(
        ScoreChange(id, "m1", side, "e-$id", ScoreChangeAction.ADD), goal("e-$id", side), state, side, 10, if (state == ConsensusState.PENDING) null else 20)
    private fun snapshot(decisions: List<ConsensusDecision>, whistles: Set<Side> = emptySet()) =
        ConsensusBook.project(decisions, whistles).let { p ->
            ConsensusSnapshot("m1", p.ledger, decisions,
                ScoreboardPayload("m1", p.officialScore.home, p.officialScore.away, p.pending.map { PendingChangeView(it.change.changeId, it.change.submittedBy, it.state) }, p.ledger),
                p.pending.map { PendingChangeView(it.change.changeId, it.change.submittedBy, it.state) }, p.finalWhistles, p.official)
        }

    @Test fun sideMappingFollowsServerPartyOrder() {
        assertEquals("HOME", facade.sideFor("team-a", "team-b", "team-a"))
        assertEquals("AWAY", facade.sideFor("team-a", "team-b", "team-b"))
        assertFailsWith<IllegalArgumentException> { facade.sideFor("team-a", "team-b", "team-c") }
    }

    @Test fun submitGoalBodyIsServerReadyAndValidated() {
        val body = facade.submitGoal("m1", "HOME", "c1", "e1", 99, 10, "key-0123456789ab")
        val parsed = json.decodeFromString(ConsensusSubmit.serializer(), body)
        assertEquals("e1", parsed.change.ledgerEventId)
        assertEquals(10, (parsed.event as LedgerEvent.Event).payload?.let { (it as EventPayload.Goal).jerseyNumber })
        assertFailsWith<IllegalArgumentException> { facade.submitGoal("m1", "HOME", "c1", "e1", 99, 100, "key-0123456789ab") }
    }

    @Test fun scoreboardKeepsPendingBandSeparateFromOfficial() {
        val pending = decision("c-home", Side.HOME, ConsensusState.PENDING)
        val line = facade.scoreboard(json.encodeToString(ConsensusSnapshot.serializer(), snapshot(listOf(pending))))
        assertTrue(line.startsWith("OFFICIAL 0-0"), line)
        assertTrue(line.contains("PENDING REVIEW: 1"), line)
        val final = snapshot(listOf(pending), Side.entries.toSet())
        assertTrue(facade.scoreboard(json.encodeToString(ConsensusSnapshot.serializer(), final)).contains("live"))
        val resolved = json.encodeToString(ConsensusSnapshot.serializer(), snapshot(listOf(decision("c-home", Side.HOME, ConsensusState.ACCEPTED)), Side.entries.toSet()))
        val official = facade.scoreboard(resolved)
        assertTrue(official.contains("OFFICIAL 1-0") && official.contains("FINAL (official)") && official.contains("PENDING REVIEW: 0"), official)
    }

    @Test fun pendingRowsLabelOwnVersusOpponentChangeWithAction() {
        val book = listOf(decision("c-home", Side.HOME, ConsensusState.PENDING), decision("c-away", Side.AWAY, ConsensusState.PENDING))
        val rows = facade.pendingRows(json.encodeToString(ConsensusSnapshot.serializer(), snapshot(book)), "HOME")
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.startsWith("c-home|Awaiting opponent:") && it.endsWith("|withdraw") }, rows.toString())
        assertTrue(rows.any { it.startsWith("c-away|Review:") && it.endsWith("|review") }, rows.toString())
    }

    @Test fun recordLedgerShowsRejectedAndWithdrawnForeverWithAuditTrail() {
        val book = listOf(decision("c-acc", Side.HOME, ConsensusState.ACCEPTED), decision("c-rej", Side.AWAY, ConsensusState.REJECTED), decision("c-wd", Side.HOME, ConsensusState.WITHDRAWN))
        val ledger = facade.recordLedger(json.encodeToString(ConsensusSnapshot.serializer(), snapshot(book)))
        assertEquals(3, ledger.lines().size)
        assertTrue(ledger.contains("REJECTED") && ledger.contains("resolved by"), ledger)
        assertTrue(ledger.contains("WITHDRAWN") && ledger.contains("resolved by"), ledger)
        assertEquals("No consensus records", facade.recordLedger(json.encodeToString(ConsensusSnapshot.serializer(), snapshot(emptyList()))))
    }

    @Test fun offlineTwoSidelineBridgeKeepsOfficialZeroUntilOpponentAccepts() {
        val pending = facade.demoSubmit("[]", "demo", "HOME", "c1", "e1", 1000, 9, 1000)
        val before = facade.demoProject(pending, "[]")
        assertTrue(facade.scoreboard(before).contains("OFFICIAL 0-0"))
        assertTrue(facade.pendingRows(before, "AWAY").single().contains("Review:"))
        assertFailsWith<IllegalArgumentException> { facade.demoReview(pending, "c1", "HOME", "accept", 1100) }
        val accepted = facade.demoReview(pending, "c1", "AWAY", "accept", 1100)
        val after = facade.demoProject(accepted, "[]")
        assertTrue(facade.scoreboard(after).contains("OFFICIAL 1-0"))
        assertTrue(facade.pendingRows(after, "AWAY").isEmpty())
        assertTrue(facade.recordLedger(after).contains("resolved by AWAY"))
    }

    @Test fun capturedGoalMapsFixtureSideToLinkSideAndPreservesLocalLedger() {
        val local = MatchDayState("coordinated:FIX-1").capture(
            LedgerEvent.Event("goal-1", Side.HOME, 1_000, EventPayload.Goal(Side.HOME, 9)))
        val localJson = MatchDayCodec.encode(local)
        val body = facade.capturedGoal(localJson, "goal-1", "team-b", "team-a",
            "team-a", "team-b", "team-b", "change-1", "idempotency-key-0123456789")
        val submit = json.decodeFromString(ConsensusSubmit.serializer(), body)
        assertEquals("FIX-1", submit.change.matchId)
        assertEquals(Side.AWAY, submit.change.submittedBy)
        assertEquals(Side.AWAY, submit.event.side)
        assertEquals(Side.AWAY, (submit.event as LedgerEvent.Event).payload.let { (it as EventPayload.Goal).side })
        assertEquals("goal-1", submit.event.eventId)
        assertEquals(local, MatchDayCodec.decode(localJson))
        val pending = ConsensusBook.submit(emptyList(), SubmissionRequest(submit.change, submit.event), Side.AWAY, 1_001)
        assertEquals("0-0", ConsensusBook.project(pending, emptySet()).officialScore.render())
        assertEquals("0-1", ConsensusBook.project(ConsensusBook.review(pending, "change-1", Side.HOME,
            ConsensusAction.ACCEPT, 1_002), emptySet()).officialScore.render())
        assertFailsWith<IllegalArgumentException> { facade.capturedGoal(localJson, "goal-1", "team-b", "team-c",
            "team-a", "team-b", "team-b", "change-1", "key") }
        assertFailsWith<IllegalArgumentException> { facade.capturedGoal(localJson, "goal-1", "team-b", "team-a",
            "team-a", "team-b", "team-a", "change-1", "key") }
        assertFailsWith<IllegalArgumentException> { facade.capturedGoal(localJson, "missing", "team-b", "team-a",
            "team-a", "team-b", "team-b", "change-1", "key") }
        val clock = MatchDayCodec.encode(local.capture(LedgerEvent.Event("clock-1", Side.HOME, 1_001,
            EventPayload.Clock(ClockTransition.HALFTIME, 60))))
        assertFailsWith<IllegalArgumentException> { facade.capturedGoal(clock, "clock-1", "team-b", "team-a",
            "team-a", "team-b", "team-b", "change-1", "idempotency-key-0123456789") }
        val invalidJersey = MatchDayCodec.encode(MatchDayState("coordinated:FIX-1").capture(
            LedgerEvent.Event("bad-jersey", Side.HOME, 1_000, EventPayload.Goal(Side.HOME, 100))))
        assertFailsWith<IllegalArgumentException> { facade.capturedGoal(invalidJersey, "bad-jersey", "team-b", "team-a",
            "team-a", "team-b", "team-b", "change-2", "idempotency-key-0123456789") }
    }

    @Test fun capturedGoalRowsOfferOnlyOwnQueuedUncorrectedGoals() {
        val homeGoal = LedgerEvent.Event("goal-home-0000001", Side.HOME, 1_000, EventPayload.Goal(Side.HOME, 9))
        val awayGoal = LedgerEvent.Event("goal-away-0000001", Side.AWAY, 1_100, EventPayload.Goal(Side.AWAY, 7))
        val card = LedgerEvent.Event("card-home-00000001", Side.HOME, 1_200, EventPayload.Card(Side.HOME, 4, CardColor.YELLOW))
        var state = MatchDayState("coordinated:FIX-1").capture(homeGoal).capture(awayGoal).capture(card)
        val stateJson = MatchDayCodec.encode(state)
        // Fixture HOME is team-b while link HOME is team-a: rows follow the fixture side.
        assertEquals(listOf("goal-home-0000001|Goal #9 (open play)"),
            facade.capturedGoalRows(stateJson, "team-b", "team-a", "team-b", facade.outboxNew()))
        assertEquals(listOf("goal-away-0000001|Goal #7 (open play)"),
            facade.capturedGoalRows(stateJson, "team-b", "team-a", "team-a", facade.outboxNew()))
        assertFailsWith<IllegalArgumentException> { facade.capturedGoalRows(stateJson, "team-b", "team-a", "team-c", facade.outboxNew()) }
        // Queued/committed/refused entries and corrected goals are never offered again.
        val queuedOutbox = facade.enqueueCapturedGoal(facade.outboxNew(), stateJson, "goal-home-0000001", "link-1",
            "team-b", "team-a", "team-a", "team-b", "team-b")
        assertTrue(facade.outboxContains(queuedOutbox, "goal-home-0000001"))
        assertEquals(emptyList(), facade.capturedGoalRows(stateJson, "team-b", "team-a", "team-b", queuedOutbox))
        assertFailsWith<IllegalArgumentException> { facade.enqueueCapturedGoal(queuedOutbox, stateJson, "goal-home-0000001", "link-1",
            "team-b", "team-a", "team-a", "team-b", "team-b") }
        val delivered = facade.outboxDelivered(queuedOutbox, "goal-home-0000001")
        assertFailsWith<IllegalArgumentException> { facade.enqueueCapturedGoal(delivered, stateJson, "goal-home-0000001", "link-1",
            "team-b", "team-a", "team-a", "team-b", "team-b") }
        val refused = facade.outboxRefuse(queuedOutbox, "goal-home-0000001", 409)
        assertFailsWith<IllegalArgumentException> { facade.enqueueCapturedGoal(refused, stateJson, "goal-home-0000001", "link-1",
            "team-b", "team-a", "team-a", "team-b", "team-b") }
        // Enqueued entry carries the exact link-side submission body and link path.
        val entry = facade.outboxHead(queuedOutbox)
        assertEquals("/v0/links/link-1/consensus/changes", facade.outboxPath(entry))
        val submit = json.decodeFromString(ConsensusSubmit.serializer(), facade.outboxBody(entry))
        assertEquals(Side.AWAY, submit.change.submittedBy)
        assertEquals("FIX-1", submit.change.matchId)
        assertEquals("goal-home-0000001", submit.change.changeId)
        // Acknowledged (delivered to broadcast, no longer queued locally) drops out of the offer.
        state = state.acknowledge(listOf(homeGoal))
        assertEquals(emptyList(), facade.capturedGoalRows(MatchDayCodec.encode(state), "team-b", "team-a", "team-b", facade.outboxNew()))
        // A corrected goal needs an explicit consensus correction, never a second ADD.
        val corrected = MatchDayCodec.encode(MatchDayState("coordinated:FIX-1").capture(homeGoal).capture(
            LedgerEvent.Correction("correction-0000001", Side.HOME, 1_300, "goal-home-0000001", homeGoal.copy(eventId = "replacement-0000001"))))
        assertEquals(emptyList(), facade.capturedGoalRows(corrected, "team-b", "team-a", "team-b", facade.outboxNew()))
    }

    @Test fun acceptedGoalRowsAndChangesTrackOnlyOwnAcceptedGoals() {
        val pending = decision("c-pending", Side.HOME, ConsensusState.PENDING)
        val acceptedHome = decision("c-acc", Side.HOME, ConsensusState.ACCEPTED)
        val acceptedAway = decision("c-away", Side.AWAY, ConsensusState.ACCEPTED)
        val snap = json.encodeToString(ConsensusSnapshot.serializer(), snapshot(listOf(pending, acceptedHome, acceptedAway)))
        val homeRows = facade.acceptedGoalRows(snap, "HOME")
        assertEquals(listOf("e-c-acc|Goal #9"), homeRows)
        val awayRows = facade.acceptedGoalRows(snap, "AWAY")
        assertEquals(listOf("e-c-away|Goal #9"), awayRows)

        // Generating a correction produces a valid ConsensusSubmit referencing the original event
        val correctBody = facade.changeGoal(snap, "HOME", "e-c-acc", "correct", "c-fix", "e-fix", "rep-fix", 2000, 11, "k-1")
        val correct = json.decodeFromString(ConsensusSubmit.serializer(), correctBody)
        assertEquals(ScoreChangeAction.CORRECT, correct.change.action)
        assertTrue(correct.event is LedgerEvent.Correction)
        assertEquals("e-c-acc", (correct.event as LedgerEvent.Correction).correctsEventId)

        // Generating a removal reproduces the original event row
        val removeBody = facade.changeGoal(snap, "HOME", "e-c-acc", "remove", "c-rm", "e-rm", "rep-rm", 2000, 0, "k-2")
        val remove = json.decodeFromString(ConsensusSubmit.serializer(), removeBody)
        assertEquals(ScoreChangeAction.REMOVE, remove.change.action)
        assertEquals((acceptedHome.event as LedgerEvent.Event).eventId, remove.change.ledgerEventId)
    }

    @Test fun acceptedRemoveAndCorrectStayPendingUntilOpponentApproves() {
        var book = facade.demoSubmit("[]", "demo", "HOME", "c1", "e1", 1000, 9, 1000)
        book = facade.demoReview(book, "c1", "AWAY", "accept", 1001)
        val start = facade.demoProject(book, "[]")
        assertEquals(listOf("e1|Goal #9"), facade.acceptedGoalRows(start, "HOME"))

        val correction = json.decodeFromString(ConsensusSubmit.serializer(),
            facade.changeGoal(start, "HOME", "e1", "correct", "c2", "e2", "r2", 1002, 11, "key-2"))
        var decisions = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ConsensusDecision.serializer()), book)
        decisions = ConsensusBook.submit(decisions, SubmissionRequest(correction.change, correction.event), Side.HOME, 1002)
        var projected = json.encodeToString(ConsensusSnapshot.serializer(), snapshot(decisions))
        assertTrue(facade.acceptedGoalRows(projected, "HOME").isEmpty()) // cannot duplicate pending edit
        assertEquals("1-0", ConsensusBook.project(decisions, emptySet()).officialScore.render())
        decisions = ConsensusBook.review(decisions, "c2", Side.AWAY, ConsensusAction.ACCEPT, 1003)
        assertEquals("1-0", ConsensusBook.project(decisions, emptySet()).officialScore.render())

        // Removing original goal leaves correction row in audit but removes its score only after review.
        // The UI doesn't offer the old goal after correction; API still accepts a historical removal.
        val original = decisions.first().event
        decisions = ConsensusBook.submit(decisions,
            SubmissionRequest(ScoreChange("c3", "demo", Side.HOME, "e1", ScoreChangeAction.REMOVE), original), Side.HOME, 1004)
        assertEquals("1-0", ConsensusBook.project(decisions, emptySet()).officialScore.render())
        decisions = ConsensusBook.review(decisions, "c3", Side.AWAY, ConsensusAction.ACCEPT, 1005)
        assertEquals("0-0", ConsensusBook.project(decisions, emptySet()).officialScore.render())
        assertEquals(3, decisions.size)
    }
}
