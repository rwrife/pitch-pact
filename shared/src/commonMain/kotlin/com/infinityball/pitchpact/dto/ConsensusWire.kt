package com.infinityball.pitchpact.dto

import com.infinityball.pitchpact.domain.*
import kotlinx.serialization.Serializable

/** Match facts and audit metadata only; no roster, guardian or availability fields. */
@Serializable data class ConsensusSubmit(
    val change: ScoreChange,
    val event: LedgerEvent,
    val idempotencyKey: String,
)
@Serializable data class ConsensusDecision(
    val change: ScoreChange,
    val event: LedgerEvent,
    val state: ConsensusState,
    val actor: Side,
    val submittedAt: Long,
    val resolvedAt: Long? = null,
)
@Serializable data class ConsensusReview(val idempotencyKey: String)
@Serializable data class ConsensusWhistle(val matchId: String, val side: Side, val idempotencyKey: String)
@Serializable data class ConsensusSnapshot(
    val matchId: String,
    val events: List<LedgerEvent>,
    val decisions: List<ConsensusDecision>,
    val officialScore: ScoreboardPayload,
    val pendingChanges: List<PendingChangeView>,
    val finalWhistles: Set<Side>,
    val official: Boolean,
)
