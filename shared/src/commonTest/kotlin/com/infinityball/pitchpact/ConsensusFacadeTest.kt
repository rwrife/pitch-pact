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
}
