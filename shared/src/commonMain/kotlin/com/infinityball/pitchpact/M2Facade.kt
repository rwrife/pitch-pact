package com.infinityball.pitchpact

import com.infinityball.pitchpact.domain.Player
import com.infinityball.pitchpact.domain.Team
import com.infinityball.pitchpact.domain.ValidationException
import com.infinityball.pitchpact.store.StoreFixtureCodec

/**
 * Swift/Compose-facing helpers for M2 (teams, rosters, uniforms).
 *
 * The iOS store maps SQL rows to shared DTOs and calls these; Android
 * Compose calls the domain objects directly (same JVM classes). String-only
 * signatures keep the ObjC bridge boring.
 *
 * Single-threaded by contract.
 */
object M2Facade {

    /** Round-trips the shared M2 fixture JSON (decode + re-encode canonical). */
    fun fixtureRoundTrip(json: String): String {
        val (teams, players) = StoreFixtureCodec.decode(json)
        return StoreFixtureCodec.encode(teams, players)
    }

    /**
     * Validates a player against a roster snapshot (JSON of the same fixture
     * shape). Returns null when valid, otherwise the shared error message.
     * The stores surface this wording verbatim on both platforms.
     */
    fun playerValidationError(rosterJson: String, candidateJson: String): String? =
        try {
            val roster = StoreFixtureCodec.decode(rosterJson).second
            val candidate = StoreFixtureCodec.decode(
                """{"teams":[],"players":[$candidateJson]}""",
            ).second.single()
            com.infinityball.pitchpact.domain.Validate.jerseyUnique(roster, candidate)
            null
        } catch (e: ValidationException) {
            e.message
        }

    /** Fixture-shaped JSON for one team + roster (used by both stores' tests). */
    fun encodeFixture(teams: List<Team>, players: List<Player>): String =
        StoreFixtureCodec.encode(teams, players)
}
