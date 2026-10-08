package com.infinityball.pitchpact.domain
import kotlinx.serialization.Serializable

/** Match facts only. Capability is an HTTP authorization header, never persisted here. */
@Serializable data class MatchSyncRequest(val matchId: String, val events: List<LedgerEvent>)
@Serializable data class MatchSyncReceipt(val matchId: String, val events: List<LedgerEvent>)
