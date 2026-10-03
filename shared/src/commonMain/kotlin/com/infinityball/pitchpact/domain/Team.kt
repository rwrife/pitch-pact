package com.infinityball.pitchpact.domain

import kotlinx.serialization.Serializable

/**
 * A soccer team managed by the app (M2). `archivedAtEpochMillis` marks a
 * soft archive — archived teams remain in the store for record keeping but
 * are hidden from active pickers. Nothing is ever silently destroyed
 * (see [TeamDeletionPreview]).
 *
 * This is LOCAL roster data. It must never serialize into broadcast or
 * registry DTOs (M5/M6); the privacy gate in PrivacyDtoTest +
 * M2PrivacyTest enforces that structurally.
 */
@Serializable
data class Team(
    val id: String,
    val name: String,
    val createdAtEpochMillis: Long,
    val archivedAtEpochMillis: Long? = null,
) {
    val isArchived: Boolean get() = archivedAtEpochMillis != null
}
