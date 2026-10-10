package com.infinityball.pitchpact.dto

import com.infinityball.pitchpact.domain.*
import kotlinx.serialization.Serializable

/** Match facts only. Roster, guardian and availability types have no wire representation. */
@Serializable data class BroadcastCreate(
    val matchId: String,
    val homeTeam: String,
    val awayTeam: String,
    val fieldPolicy: String = "number-only",
    val teamLinkId: String? = null,
    val idempotencyKey: String,
)
@Serializable data class BroadcastStarted(val spectatorCode: String, val broadcastSessionKey: String, val expiresAt: Long)
@Serializable data class BroadcastUpdate(
    val seq: Long,
    val idempotencyKey: String,
    val events: List<LedgerEvent>,
    val decisions: List<StoredScoreDecision>,
    val clock: MatchClock,
)
@Serializable data class BroadcastAck(val ackSeq: Long, val expiresAt: Long)
@Serializable data class SpectatorView(
    val homeTeam: String,
    val awayTeam: String,
    val clock: MatchClock,
    val officialScore: ScoreboardPayload,
    val pendingChanges: List<PendingChangeView>,
    val events: List<LedgerEvent>,
    val expiresAt: Long,
    val spectatorCount: Int,
)
