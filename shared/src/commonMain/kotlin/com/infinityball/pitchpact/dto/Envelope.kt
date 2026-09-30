package com.infinityball.pitchpact.dto

import com.infinityball.pitchpact.domain.ConsensusState
import com.infinityball.pitchpact.domain.LedgerEvent
import com.infinityball.pitchpact.domain.Side
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Protocol version negotiated inside every envelope. Bump deliberately;
 * servers must echo the version they serve (see docs/protocol.md §1).
 */
const val PROTOCOL_VERSION: String = "v0"

/**
 * Wire envelope shared verbatim by the iOS app, Android app, and the Ktor
 * server (one source of truth, three consumers).
 *
 * ```json
 * { "protocol": "v0", "type": "<dto-kind>", "idempotencyKey": "<uuid>", "payload": {...} }
 * ```
 */
@Serializable
data class Envelope(
    val protocol: String = PROTOCOL_VERSION,
    val type: String,
    val idempotencyKey: String,
    val payload: EnvelopePayload,
)

/**
 * Envelope payload kinds present at protocol v0. Feature milestones (M5–M7)
 * add broadcast/registry/consensus payloads here so all three targets keep
 * compiling against one definition.
 */
@Serializable
sealed class EnvelopePayload

/** Health probe payload echoed by the server stub. */
@Serializable
@SerialName("health")
data class HealthPayload(
    val status: String,
    val protocol: String = PROTOCOL_VERSION,
) : EnvelopePayload()

/** Generic echo payload proving the DTO loop works end to end. */
@Serializable
@SerialName("echo")
data class EchoPayload(val request: String) : EnvelopePayload()

/**
 * Official scoreboard snapshot as broadcast to spectators. Contains ONLY match
 * facts: no roster, no guardian contacts, no availability (privacy contract;
 * the types are not even importable into broadcast DTOs — see PrivacyDtoTest).
 */
@Serializable
@SerialName("scoreboard")
data class ScoreboardPayload(
    val matchId: String,
    val homeScore: Int,
    val awayScore: Int,
    val pendingChanges: List<PendingChangeView> = emptyList(),
    val events: List<LedgerEvent> = emptyList(),
) : EnvelopePayload()

@Serializable
data class PendingChangeView(
    val changeId: String,
    val submittedBy: Side,
    val state: ConsensusState,
)

/** Shared Json config — same tolerance/encoding on apps and server. */
object PitchPactJson {
    val codec: Json = Json {
        explicitNulls = false
        encodeDefaults = true
        ignoreUnknownKeys = true // forward-tolerant reads within a protocol version
        classDiscriminator = "type"
    }
}
