package com.infinityball.pitchpact.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Named kit colors — stored as enum names; free-text notes carry nuance. */
@Serializable
enum class KitColor {
    @SerialName("white") WHITE,
    @SerialName("black") BLACK,
    @SerialName("red") RED,
    @SerialName("blue") BLUE,
    @SerialName("green") GREEN,
    @SerialName("yellow") YELLOW,
    @SerialName("orange") ORANGE,
    @SerialName("purple") PURPLE,
    @SerialName("pink") PINK,
    @SerialName("grey") GREY,
}

/**
 * Kit colors for one situation (home shirt / away shirt / keeper kit).
 * `alternate` supports clash planning ("wear blue if opponent wears red").
 */
@Serializable
data class Kit(
    val primary: KitColor,
    val alternate: KitColor? = null,
)

/**
 * Per-opponent uniform requirement (M2): what OUR team must wear when facing
 * [opponentName], plus an equipment checklist shared with the squad.
 */
@Serializable
data class UniformRequirement(
    val id: String,
    val teamId: String,
    val opponentName: String,
    val homeKit: Kit,
    val awayKit: Kit,
    val keeperKit: Kit? = null,
    /** Free-text checklist lines (e.g. "shinguards", "size 4 ball"). */
    val equipmentChecklist: List<String> = emptyList(),
    val notes: String = "",
)

/** Result of a team-deletion guard check (M2 AC: no silent destruction). */
@Serializable
data class TeamDeletionPreview(
    val teamId: String,
    val playerCount: Int,
    val uniformRequirementCount: Int,
    val guardianContactCount: Int,
    /** Future-proof: fixtures referencing the team (M3+ stores fill this). */
    val fixtureCount: Int = 0,
) {
    val blockingCount: Int
        get() = playerCount + uniformRequirementCount + guardianContactCount + fixtureCount
}

/** User-visible domain errors, shared so both UIs show identical wording. */
class ValidationException(val code: String, message: String) : Exception(message)
