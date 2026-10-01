package com.infinityball.pitchpact.domain

import kotlinx.serialization.Serializable

/**
 * A roster player (M2). `jerseyNumber` is unique per team — uniqueness is
 * validated by [Validate.validatePlayer] against the current roster and
 * enforced again at the store layer (unique index) on both platforms.
 *
 * Player rows intentionally carry NO guardian fields; those live in the
 * separate private [GuardianContact] entity so accidental roster serialization
 * can never include contact data.
 */
@Serializable
data class Player(
    val id: String,
    val teamId: String,
    val name: String,
    val jerseyNumber: Int,
    val createdAtEpochMillis: Long,
)
