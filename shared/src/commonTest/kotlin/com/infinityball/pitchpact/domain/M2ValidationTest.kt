package com.infinityball.pitchpact.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * M2 validation rules: jersey range + per-team uniqueness, blank-name and
 * guardian-reachability guards. Both stores rely on these being the single
 * source of truth for error wording.
 */
class M2ValidationTest {

    private fun player(id: String = "p1", jersey: Int = 7, name: String = "Ada") =
        Player(id = id, teamId = "t1", name = name, jerseyNumber = jersey, createdAtEpochMillis = 0)

    @Test
    fun jerseyNumbersOutside1To99Rejected() {
        assertFailsWith<ValidationException> { Validate.jerseyNumber(0) }
        assertFailsWith<ValidationException> { Validate.jerseyNumber(-1) }
        assertFailsWith<ValidationException> { Validate.jerseyNumber(100) }
        Validate.jerseyNumber(1)
        Validate.jerseyNumber(99)
    }

    @Test
    fun jerseyClashNamesTheOffender() {
        val roster = listOf(player(id = "a", jersey = 9, name = "Bo"))
        val e = assertFailsWith<ValidationException> {
            Validate.jerseyUnique(roster, player(id = "b", jersey = 9))
        }
        assertEquals("jersey.clash", e.code)
        assertTrue(e.message!!.contains("Bo"), "message should name the clash holder: ${e.message}")
    }

    @Test
    fun editingSamePlayerSameJerseyIsNoOp() {
        val p = player(id = "a", jersey = 9)
        Validate.jerseyUnique(listOf(p), p) // must NOT throw
    }

    @Test
    fun blankNamesRejectedEverywhere() {
        assertFailsWith<ValidationException> { Validate.teamName("  ") }
        assertFailsWith<ValidationException> { Validate.playerName("") }
        assertFailsWith<ValidationException> { Validate.opponentName(" ") }
        assertFailsWith<ValidationException> { Validate.guardianName("") }
    }

    @Test
    fun guardianNeedsAtLeastOneRoute() {
        val bare = GuardianContact("g1", "p1", "Sam", phone = "", email = "")
        assertFailsWith<ValidationException> { Validate.guardianReachable(bare) }
        Validate.guardianReachable(bare.copy(phone = "555"))
        Validate.guardianReachable(bare.copy(email = "s@x.y"))
    }

    @Test
    fun checklistNormalizesTrimsDedupes() {
        val out = Validate.normalizedChecklist(listOf(" shinguards ", "", "ball", "shinguards"))
        assertEquals(listOf("shinguards", "ball"), out)
    }

    @Test
    fun deletionPreviewTotalsBlockingRows() {
        val preview = TeamDeletionPreview("t1", playerCount = 11, uniformRequirementCount = 2, guardianContactCount = 9)
        assertEquals(22, preview.blockingCount)
        assertEquals(0, TeamDeletionPreview("t1", 0, 0, 0).blockingCount)
    }
}
