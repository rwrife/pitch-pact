package com.infinityball.pitchpact.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Consensus lifecycle for a score change submitted by one sideline.
 *
 * States: pending → accepted | rejected | withdrawn. The OFFICIAL scoreboard
 * derives only from accepted changes; the full ledger always keeps every
 * submission regardless of outcome (product contract — never simplify this).
 */
@Serializable
enum class ConsensusState {
    @SerialName("pending") PENDING,
    @SerialName("accepted") ACCEPTED,
    @SerialName("rejected") REJECTED,
    @SerialName("withdrawn") WITHDRAWN,
}

/** Actions a party may attempt on a pending-or-resolved change. */
@Serializable
enum class ConsensusAction {
    /** By the OPPOSING side. */
    @SerialName("accept") ACCEPT,

    /** By the OPPOSING side. */
    @SerialName("reject") REJECT,

    /** By the submitting side, only before resolution. */
    @SerialName("withdraw") WITHDRAW,
}

/**
 * One submitted score change. `submittedBy` is the submitting side; only the
 * opposing side may accept/reject. The referenced ledger event remains in the
 * match ledger forever regardless of consensus outcome.
 */
@Serializable
data class ScoreChange(
    val changeId: String,
    val matchId: String,
    val submittedBy: Side,
    /** The ledger event describing the goal add/remove/correct being proposed. */
    val ledgerEventId: String,
    val action: ScoreChangeAction,
)

@Serializable
enum class ScoreChangeAction {
    @SerialName("add") ADD,
    @SerialName("remove") REMOVE,
    @SerialName("correct") CORRECT,
}

/**
 * Result of attempting a transition. `allowed=false` means the action is
 * illegal for the current state/actor and state did NOT change (never
 * silently coerced).
 */
data class ConsensusTransition(
    val allowed: Boolean,
    val state: ConsensusState,
    val reason: String,
)

/**
 * Exhaustive consensus state machine. Every (state, actor-role, action)
 * combination is handled explicitly — there is deliberately NO auto-accept
 * and no clock-order merge heuristic: a change is official only when the
 * opposing side accepts.
 *
 * Single-threaded by contract (UI thread / explicit calls only).
 */
class ConsensusRecord(val change: ScoreChange) {
    var state: ConsensusState = ConsensusState.PENDING
        private set

    private enum class Role { SUBMITTER, OPPOSING }

    /**
     * Attempt [action] performed by [actor].
     *
     * Accept/reject are legal only for the OPPOSING side; withdraw is legal
     * only for the submitting side and only while pending. Terminal states
     * (accepted/rejected/withdrawn) are immutable: corrections or resubmissions
     * are NEW records, never in-place reactivation.
     */
    fun apply(actor: Side, action: ConsensusAction): ConsensusTransition {
        val role = if (actor == change.submittedBy) Role.SUBMITTER else Role.OPPOSING
        val transition = decide(state, role, action)
        if (transition.allowed) state = transition.state
        return transition
    }

    private fun decide(
        from: ConsensusState,
        role: Role,
        action: ConsensusAction,
    ): ConsensusTransition = when (from) {
        ConsensusState.PENDING -> when (role) {
            Role.OPPOSING -> when (action) {
                ConsensusAction.ACCEPT -> ok(ConsensusState.ACCEPTED, "opposing side accepted")
                ConsensusAction.REJECT -> ok(ConsensusState.REJECTED, "opposing side rejected")
                ConsensusAction.WITHDRAW -> no(from, "withdraw is only allowed for the submitting side")
            }
            Role.SUBMITTER -> when (action) {
                ConsensusAction.WITHDRAW -> ok(ConsensusState.WITHDRAWN, "submitter withdrew")
                ConsensusAction.ACCEPT -> no(from, "submitting side cannot accept its own change")
                ConsensusAction.REJECT -> no(from, "submitting side cannot reject its own change")
            }
        }
        ConsensusState.ACCEPTED -> when (role) {
            Role.OPPOSING -> no(from, "change already accepted")
            Role.SUBMITTER -> no(from, "accepted changes are official; submit a new change to correct")
        }
        ConsensusState.REJECTED -> when (role) {
            Role.OPPOSING -> no(from, "change already rejected")
            Role.SUBMITTER -> no(from, "rejected changes are immutable; the ledger keeps the record")
        }
        ConsensusState.WITHDRAWN -> when (role) {
            Role.OPPOSING -> no(from, "withdrawn changes are immutable; submit a new change")
            Role.SUBMITTER -> no(from, "withdrawn changes are immutable; submit a new change")
        }
    }

    private fun ok(to: ConsensusState, why: String) = ConsensusTransition(true, to, why)
    private fun no(kept: ConsensusState, why: String) = ConsensusTransition(false, kept, why)
}

/**
 * Official scoreboard derivation: only ACCEPTED changes are counted. Pending
 * changes are surfaced separately (labeled) for display, never folded into the
 * official number.
 */
data class OfficialScore(
    val home: Int,
    val away: Int,
) {
    fun render(): String = "$home-$away"
}

object ConsensusScoring {
    /**
     * Derive the official score from accepted changes over the ledger.
     * ADD adds one goal to the change-submitter's side, REMOVE subtracts one.
     * (M1 keeps the arithmetic minimal but real; richer goal-type/own-goal
     * accounting lands with the match-day engine.)
     */
    fun officialScore(
        ledger: MatchLedger,
        changes: List<ConsensusRecord>,
    ): OfficialScore {
        val effectiveIds = ledger.effectiveEvents()
            .filterIsInstance<LedgerEvent.Event>()
            .map { it.eventId }
            .toSet()
        var home = 0
        var away = 0
        for (record in changes) {
            if (record.state != ConsensusState.ACCEPTED) continue
            val ev = ledger.events.filterIsInstance<LedgerEvent.Event>()
                .firstOrNull { it.eventId == record.change.ledgerEventId }
                ?: continue
            if (ev.eventId !in effectiveIds) continue // superseded by a correction
            val delta = when (record.change.action) {
                ScoreChangeAction.ADD -> 1
                ScoreChangeAction.REMOVE -> -1
                ScoreChangeAction.CORRECT -> continue // corrections carry their own add/remove
            }
            when (record.change.submittedBy) {
                Side.HOME -> home += delta
                Side.AWAY -> away += delta
            }
        }
        return OfficialScore(home.coerceAtLeast(0), away.coerceAtLeast(0))
    }
}
