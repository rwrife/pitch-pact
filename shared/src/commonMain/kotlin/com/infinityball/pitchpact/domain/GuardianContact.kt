package com.infinityball.pitchpact.domain

import kotlinx.serialization.Serializable

/**
 * PRIVATE youth-privacy value type (M2). Guardian contacts exist ONLY as a
 * local entity type and are contractually excluded from every broadcast and
 * registry DTO — the sealed [EnvelopePayload] hierarchy never references
 * this type, and M2PrivacyTest fails the build if a guardian field name ever
 * appears in a wire descriptor or a guardian payload decodes as a broadcast
 * payload.
 *
 * Persistence keeps it in its own table on both platforms so export/backup
 * slices (M8) can redact it independently of the roster.
 */
@Serializable
data class GuardianContact(
    val id: String,
    val playerId: String,
    val guardianName: String,
    /** Free-form dial string as typed by the manager; never validated online. */
    val phone: String = "",
    val email: String = "",
    val notes: String = "",
)
