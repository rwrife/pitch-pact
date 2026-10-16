package com.infinityball.pitchpact

import com.infinityball.pitchpact.domain.*
import com.infinityball.pitchpact.dto.*
import kotlinx.serialization.builtins.ListSerializer

// ponytail: Demo book stays test-only; native scorer uses server snapshots.

/**
 * UI-thread bridge for the two-sideline consensus flow. Platforms own transport
 * and secret storage; every wording/side/score rule lives here.
 */
class ConsensusFacade {
    private val json = PitchPactJson.codec

    /** Link first team is HOME, second AWAY (matches server ConsensusBook.sideOf). */
    fun sideFor(firstTeamId: String, secondTeamId: String, myTeamId: String): String =
        ConsensusBook.sideOf(listOf(firstTeamId, secondTeamId), myTeamId).name

    @Throws(IllegalArgumentException::class)
    fun submitGoal(matchId: String, side: String, changeId: String, eventId: String, epoch: Long, jersey: Int, idempotencyKey: String): String {
        require(jersey in 0..99) { "Jersey number must be 0-99" }
        val s = Side.valueOf(side)
        val event = LedgerEvent.Event(eventId, s, epoch, EventPayload.Goal(s, jersey))
        val change = ScoreChange(changeId, matchId, s, eventId, ScoreChangeAction.ADD)
        return json.encodeToString(ConsensusSubmit.serializer(), ConsensusSubmit(change, event, idempotencyKey))
    }
    fun reviewBody(idempotencyKey: String): String = json.encodeToString(ConsensusReview.serializer(), ConsensusReview(idempotencyKey))
    fun whistleBody(matchId: String, side: String, idempotencyKey: String): String =
        json.encodeToString(ConsensusWhistle.serializer(), ConsensusWhistle(matchId, Side.valueOf(side), idempotencyKey))

    /** Official score first, then a separately labeled pending band. Never merges pending into official. */
    fun scoreboard(snapshotJson: String): String {
        val s = json.decodeFromString(ConsensusSnapshot.serializer(), snapshotJson)
        val label = if (s.official) "FINAL (official)" else "live"
        return "OFFICIAL ${s.officialScore.homeScore}-${s.officialScore.awayScore} · $label\nPENDING REVIEW: ${s.pendingChanges.size}"
    }

    /** Pending band: one `changeId|text` row per pending change, for the viewer side to accept/reject (or withdraw if own). */
    fun pendingRows(snapshotJson: String, mySide: String): List<String> {
        val s = json.decodeFromString(ConsensusSnapshot.serializer(), snapshotJson)
        val me = Side.valueOf(mySide)
        return s.decisions.filter { it.state == ConsensusState.PENDING }.map { d ->
            val mine = d.change.submittedBy == me
            "${d.change.changeId}|${if (mine) "Awaiting opponent: " else "Review: "}${describe(d)}|${if (mine) "withdraw" else "review"}"
        }
    }

    /** Record ledger: every decision including rejected/withdrawn, forever. */
    fun recordLedger(snapshotJson: String): String {
        val s = json.decodeFromString(ConsensusSnapshot.serializer(), snapshotJson)
        return s.decisions.joinToString("\n") { d ->
            "${d.state.name} · ${describe(d)} · by ${d.change.submittedBy}" +
                (d.resolvedAt?.let { " · resolved by ${d.actor}" } ?: " · awaiting opponent")
        }.ifEmpty { "No consensus records" }
    }

    // ---- Offline two-sideline demo book (XCUITest surface) --------------------------------
    // Same ConsensusBook rules the server runs, in-process and transport-free so
    // the native submit/accept journey can drive them on-device. `bookJson` is a
    // list of ConsensusDecision; `[]` starts a match.

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun demoSubmit(bookJson: String, matchId: String, side: String, changeId: String, eventId: String, epoch: Long, jersey: Int, now: Long): String {
        val s = Side.valueOf(side)
        val event = LedgerEvent.Event(eventId, s, epoch, EventPayload.Goal(s, jersey))
        val change = ScoreChange(changeId, matchId, s, eventId, ScoreChangeAction.ADD)
        return encodeBook(ConsensusBook.submit(decodeBook(bookJson), SubmissionRequest(change, event), s, now))
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun demoReview(bookJson: String, changeId: String, actor: String, action: String, now: Long): String =
        encodeBook(ConsensusBook.review(decodeBook(bookJson), changeId, Side.valueOf(actor), ConsensusAction.valueOf(action.uppercase()), now))

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun demoProject(bookJson: String, whistlesJson: String): String {
        val decisions = decodeBook(bookJson)
        val whistles = json.decodeFromString(ListSerializer(Side.serializer()), whistlesJson).toSet()
        val p = ConsensusBook.project(decisions, whistles)
        val pending = p.pending.map { PendingChangeView(it.change.changeId, it.change.submittedBy, it.state) }
        return json.encodeToString(ConsensusSnapshot.serializer(), ConsensusSnapshot(
            "demo", p.ledger, decisions,
            ScoreboardPayload("demo", p.officialScore.home, p.officialScore.away, pending, p.ledger),
            pending, p.finalWhistles, p.official))
    }

    private fun decodeBook(bookJson: String): List<ConsensusDecision> =
        json.decodeFromString(ListSerializer(ConsensusDecision.serializer()), bookJson)

    private fun encodeBook(decisions: List<ConsensusDecision>): String =
        json.encodeToString(ListSerializer(ConsensusDecision.serializer()), decisions)

    private fun describe(d: ConsensusDecision): String {
        val p = (d.event as? LedgerEvent.Event)?.payload as? EventPayload.Goal
        return "${d.change.action.name.lowercase()} goal ${p?.jerseyNumber?.let { "#$it" } ?: ""} (${d.change.submittedBy})".replace("  ", " ")
    }
}
