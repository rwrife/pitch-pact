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
