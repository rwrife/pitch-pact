package com.infinityball.pitchpact.domain

import com.infinityball.pitchpact.dto.ConsensusDecision

/**
 * M7 wiring: turns raw two-sideline submissions into the official scoreboard.
 *
 * The server and both apps run THIS code — the HTTP layer stores and forwards,
 * it decides nothing. Rules (product contract, never simplify):
 *  - a change submitted by one side is PENDING until the OPPOSING side accepts;
 *  - the official scoreboard derives from accepted-only state;
 *  - rejected / withdrawn changes and every underlying ledger event stay in the
 *    record ledger forever;
 *  - the match becomes `official` only when both sides have blown the final
 *    whistle AND nothing is still pending.
 *
 * Single-threaded by contract (explicit calls from the UI thread / one server
 * monitor at a time).
 */
data class ConsensusProjection(
    val officialScore: OfficialScore,
    val pending: List<ConsensusDecision>,
    val ledger: List<LedgerEvent>,
    val finalWhistles: Set<Side>,
    val official: Boolean,
) {
    /** Record-keeping view: every submission with its outcome, forever. */
    fun recordLedger(): String = ledger.joinToString("\n") { it.eventId }
}

object ConsensusBook {
    fun sideOf(parties: List<String>, party: String): Side {
        val index = parties.indexOf(party)
        require(index >= 0) { "Party is not part of this link" }
        return if (index == 0) Side.HOME else Side.AWAY
    }

    /**
     * Accept a submission from [submitter]. Replays (same changeId + identical
     * content) are idempotent no-ops; a changed payload under a used changeId is
     * a conflict, never a silent overwrite. Both sides may hold pending changes
     * at the same instant — that is legal and stays official-neutral.
     */
    fun submit(
        decisions: List<ConsensusDecision>,
        submit: SubmissionRequest,
        submitter: Side,
        now: Long,
    ): List<ConsensusDecision> {
        require(submit.change.submittedBy == submitter) { "Only the submitting sideline may submit this change" }
        require(submit.event.side == submitter) { "Event side must match the submitting sideline" }
        val prior = decisions.firstOrNull { it.change.changeId == submit.change.changeId }
        if (prior != null) {
            require(prior.change == submit.change && prior.event == submit.event && prior.change.submittedBy == submitter) {
                "changeId already used for a different submission"
            }
            return decisions // idempotent replay
        }
        require(submit.change.matchId.isNotBlank() && submit.change.changeId.isNotBlank())
        require(submit.change.ledgerEventId == submit.event.eventId)
        require(when (submit.change.action) {
            ScoreChangeAction.ADD -> submit.event is LedgerEvent.Event && submit.event.payload is EventPayload.Goal
            ScoreChangeAction.CORRECT -> submit.event is LedgerEvent.Correction
            // A removal references an event BOTH sides can already see: the exact
            // row must exist in the shared book (no removing phantoms).
            ScoreChangeAction.REMOVE -> submit.event is LedgerEvent.Event &&
                decisions.any { it.event == submit.event }
        }) { "Score action does not match ledger event" }
        validateLedger(decisions.map { it.event } + submit.event)
        return decisions + ConsensusDecision(
            change = submit.change, event = submit.event, state = ConsensusState.PENDING,
            actor = submitter, submittedAt = now, resolvedAt = null,
        )
    }

    /**
     * Apply an accept/reject/withdraw by [actor]. Illegal transitions are
     * refused loudly (never coerced): submitter cannot accept its own change,
     * the opposing side cannot withdraw, terminal states are immutable.
     */
    fun review(
        decisions: List<ConsensusDecision>,
        changeId: String,
        actor: Side,
        action: ConsensusAction,
        now: Long,
    ): List<ConsensusDecision> {
        val index = decisions.indexOfFirst { it.change.changeId == changeId }
        require(index >= 0) { "Unknown change $changeId" }
        val current = decisions[index]
        // Replays of the same successful decision are idempotent.
        if (current.state != ConsensusState.PENDING) {
            val replay = current.actor == actor && current.state == when (action) {
                ConsensusAction.ACCEPT -> ConsensusState.ACCEPTED
                ConsensusAction.REJECT -> ConsensusState.REJECTED
                ConsensusAction.WITHDRAW -> ConsensusState.WITHDRAWN
            }
            require(replay) { "Change $changeId is already ${current.state.name.lowercase()}" }
            return decisions
        }
        val record = ConsensusRecord(current.change)
        val transition = record.apply(actor, action)
        require(transition.allowed) { transition.reason }
        val next = current.copy(state = transition.state, actor = actor, resolvedAt = now)
        return decisions.toMutableList().also { it[index] = next }
    }

    /** A sideline blows the final whistle; official requires BOTH whistles and no pending work. */
    fun whistle(whistles: Set<Side>, side: Side): Set<Side> = whistles + side

    /**
     * Full recompute from scratch. The official score is rebuilt from the
     * accepted-only ledger every time — no cached counters, so a replay of the
     * whole record yields the identical scoreboard.
     */
    fun project(
        decisions: List<ConsensusDecision>,
        whistles: Set<Side>,
    ): ConsensusProjection {
        val ledger = validateLedger(decisions.map { it.event })
        val records = decisions.map { decision ->
            ConsensusRecord(decision.change).also { record ->
                val opposing = if (decision.change.submittedBy == Side.HOME) Side.AWAY else Side.HOME
                when (decision.state) {
                    ConsensusState.ACCEPTED -> require(record.apply(opposing, ConsensusAction.ACCEPT).allowed)
                    ConsensusState.REJECTED -> require(record.apply(opposing, ConsensusAction.REJECT).allowed)
                    ConsensusState.WITHDRAWN -> require(record.apply(decision.change.submittedBy, ConsensusAction.WITHDRAW).allowed)
                    ConsensusState.PENDING -> Unit
                }
            }
        }
        val score = ConsensusScoring.officialScore(ledger, records)
        val pending = decisions.filter { it.state == ConsensusState.PENDING }
        return ConsensusProjection(
            officialScore = score,
            pending = pending,
            ledger = ledger.events,
            finalWhistles = whistles,
            official = whistles.containsAll(Side.entries) && pending.isEmpty(),
        )
    }

    /** Shared ledger validation: duplicate ids with different content are refused. */
    private fun validateLedger(events: List<LedgerEvent>): MatchLedger {
        val ledger = MatchLedger()
        ledger.replayAll(events)
        return ledger
    }
}

/** Wire-independent submission input (the server DTO wraps the same three fields). */
data class SubmissionRequest(val change: ScoreChange, val event: LedgerEvent)
