package com.infinityball.pitchpact.domain

import kotlinx.serialization.Serializable

/**
 * Shared validation rules for M2 entities. Both platform stores call these
 * BEFORE persisting; the UI layers compute nothing (business-rules-in-shared
 * contract).
 */
object Validate {
    /** Regulation youth jersey numbers: 1..99. */
    fun jerseyNumber(number: Int) {
        if (number !in 1..99) {
            throw ValidationException("jersey.out_of_range", "Jersey number must be 1..99, got $number")
        }
    }

    /**
     * Jersey uniqueness per team, checked against the current roster. The
     * edited player's own id is excluded so saving without changing the
     * number is a no-op. Throws with the offending existing holder so the UI
     * can name the clash.
     */
    fun jerseyUnique(roster: List<Player>, player: Player) {
        jerseyNumber(player.jerseyNumber)
        val clash = roster.firstOrNull {
            it.id != player.id && it.jerseyNumber == player.jerseyNumber
        }
        if (clash != null) {
            throw ValidationException(
                "jersey.clash",
                "Jersey #${player.jerseyNumber} already worn by ${clash.name}",
            )
        }
    }

    fun teamName(name: String) {
        if (name.isBlank()) throw ValidationException("team.name.blank", "Team name is required")
    }

    fun playerName(name: String) {
        if (name.isBlank()) throw ValidationException("player.name.blank", "Player name is required")
    }

    fun opponentName(name: String) {
        if (name.isBlank()) throw ValidationException("uniform.opponent.blank", "Opponent name is required")
    }

    fun guardianName(name: String) {
        if (name.isBlank()) throw ValidationException("guardian.name.blank", "Guardian name is required")
    }

    /** A guardian row must carry at least one dial route. */
    fun guardianReachable(contact: GuardianContact) {
        if (contact.phone.isBlank() && contact.email.isBlank()) {
            throw ValidationException(
                "guardian.unreachable",
                "Guardian needs a phone or email",
            )
        }
    }

    /** Equipment checklist lines are trimmed and non-blank (deduped). */
    fun normalizedChecklist(lines: List<String>): List<String> =
        lines.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
