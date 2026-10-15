package com.infinityball.pitchpact

import com.infinityball.pitchpact.domain.*
import com.infinityball.pitchpact.dto.*

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

    private fun describe(d: ConsensusDecision): String {
        val p = (d.event as? LedgerEvent.Event)?.payload as? EventPayload.Goal
        return "${d.change.action.name.lowercase()} goal ${p?.jerseyNumber?.let { "#$it" } ?: ""} (${d.change.submittedBy})".replace("  ", " ")
    }
}
