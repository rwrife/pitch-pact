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
     * Own goals belong to the opposing beneficiary. Accepted corrections replace
     * accepted goals; pending corrections never alter the official projection.
     */
    fun officialScore(
        ledger: MatchLedger,
        changes: List<ConsensusRecord>,
    ): OfficialScore {
        val acceptedGoals = linkedMapOf<String, EventPayload.Goal?>()
        val accepted = changes.filter { it.state == ConsensusState.ACCEPTED }
        // Replacement IDs retain their original ancestry even when an intermediate
        // correction is pending or rejected. Only accepted payloads are projected.
        val roots = mutableMapOf<String, String>()
        for (row in ledger.events) {
            val root = when (row) {
                is LedgerEvent.Event -> row.eventId
                is LedgerEvent.Correction -> roots.getValue(row.correctsEventId).also {
                    roots[row.replacement.eventId] = it
                }
            }
            roots[row.eventId] = root
            val records = accepted.filter { it.change.ledgerEventId == row.eventId }
            records.forEach { require(row.side == it.change.submittedBy) }
            val actions = records.map { it.change.action }.toSet()
            if (ScoreChangeAction.ADD in actions && row is LedgerEvent.Event) {
                val goal = row.payload as? EventPayload.Goal
                if (goal != null) acceptedGoals[root] = goal
            }
            if (ScoreChangeAction.CORRECT in actions && row is LedgerEvent.Correction && acceptedGoals.containsKey(root)) {
                acceptedGoals[root] = row.replacement.payload as? EventPayload.Goal
            }
            // Multiple decisions for the same row are idempotent; accepted removal
            // wins over an accepted addition without relying on arrival order.
            if (ScoreChangeAction.REMOVE in actions) acceptedGoals.remove(root)
        }
        val home = acceptedGoals.values.filterNotNull().count { beneficiary(it) == Side.HOME }
        val away = acceptedGoals.values.filterNotNull().count { beneficiary(it) == Side.AWAY }
        return OfficialScore(home.coerceAtLeast(0), away.coerceAtLeast(0))
    }
}
