package com.infinityball.pitchpact.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class SchedulingTest {
    private fun fixture(id: String, home: String = "A", away: String = "B", field: String? = "F", start: Long = 100_000L) =
        Fixture(id, "Game $id", home, away, start, 60, field)

    @Test fun deterministicRoundRobinsAndByes() {
        for (n in 2..5) {
            val ids = (1..n).map { "T$it" }
            val first = Scheduling.draft(ids, TournamentFormat.ROUND_ROBIN)
            assertEquals(first, Scheduling.draft(ids, TournamentFormat.ROUND_ROBIN))
            assertEquals(n * (n - 1) / 2, first.count { it.homeTeamId != null && it.awayTeamId != null })
            assertEquals(if (n % 2 == 1) n else 0, first.count { it.homeTeamId == null || it.awayTeamId == null })
            assertEquals(n - 1 + n % 2, first.maxOf { it.round })
            val pairs = first.filter { it.homeTeamId != null && it.awayTeamId != null }
                .map { setOf(it.homeTeamId, it.awayTeamId) }.toSet()
            assertEquals(n * (n - 1) / 2, pairs.size)
        }
    }

    @Test fun bracketKeepsByesAndUnknownWinnersExplicit() {
        val draft = Scheduling.draft(listOf("A", "B", "C", "D", "E"), TournamentFormat.SINGLE_ELIMINATION)
        assertEquals(7, draft.size)
        assertEquals(3, draft.count { it.round == 1 && (it.homeTeamId == null || it.awayTeamId == null) })
        assertTrue(draft.filter { it.round > 1 }.all { it.homeTeamId == null && it.awayTeamId == null })
    }

    @Test fun overlapMatrixNamesClashAndExcludesAdjacencyAndSelf() {
        val base = fixture("base")
        assertEquals("Team and field overlap with Game base", Scheduling.conflicts(fixture("new"), listOf(base)).single().reason)
        assertEquals("Field overlap with Game base", Scheduling.conflicts(fixture("new", "C", "D"), listOf(base)).single().reason)
        assertEquals("Team overlap with Game base", Scheduling.conflicts(fixture("new", "C", "A", "G"), listOf(base)).single().reason)
        assertTrue(Scheduling.conflicts(fixture("new", "C", "D", "G"), listOf(base)).isEmpty())
        assertTrue(Scheduling.conflicts(fixture("new", start = 100_000L + 60 * 60_000L), listOf(base)).isEmpty())
        assertTrue(Scheduling.conflicts(base, listOf(base)).isEmpty())
    }

    @Test fun dstInstantReminderUsesElapsedTimeNotWallTime() {
        // America/New_York spring-forward: 01:30 EST to 03:30 EDT is one hour.
        val preJump = 1741501800000L // 2025-03-09 06:30 UTC
        val postJump = preJump + 60 * 60_000L
        val f = fixture("dst").copy(startEpochMillis = postJump, reminderMinutesBefore = 60)
        assertEquals(preJump, Scheduling.reminderEpochMillis(f))
        assertEquals(null, Scheduling.reminderEpochMillis(f.copy(reminderMinutesBefore = null)))
        // Fall-back repeated local hour: 01:30 EDT to 01:30 EST is also one hour.
        val fall = fixture("fall").copy(startEpochMillis = 1762065000000L, reminderMinutesBefore = 60)
        assertEquals(fall.startEpochMillis - 3600000, Scheduling.reminderEpochMillis(fall))
    }

    @Test fun schedulingCannotRewriteAnOfficialOrPendingScore() {
        val official = fixture("o").copy(resultStatus = ResultStatus.OFFICIAL, officialHome = 1, officialAway = 0)
        val violation = assertFailsWith<ValidationException> {
            Scheduling.validateEdit(official, official.copy(homeTeamId = "C"))
        }
        assertEquals("fixture.result.locked", violation.code)
        assertFailsWith<ValidationException> { Scheduling.validateEdit(official, official.copy(officialAway = 2)) }
        Scheduling.validateEdit(official, official.copy(title = "Renamed"))
        val pending = fixture("p").copy(resultStatus = ResultStatus.PENDING)
        assertFailsWith<ValidationException> { Scheduling.validateEdit(pending, pending.copy(resultStatus = ResultStatus.OFFICIAL, officialHome = 0, officialAway = 0)) }
    }

    @Test fun swiftOmittedNilLocationDecodesForStandings() {
        val wire = """[{"id":"p","title":"Pending","homeTeamId":"A","awayTeamId":"B","startEpochMillis":1,"durationMinutes":90,"resultStatus":"PENDING"}]"""
        val result = com.infinityball.pitchpact.SchedulingFacade().standings(listOf("A", "B"), wire)
        val rows = kotlinx.serialization.json.Json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(Standing.serializer()), result)
        assertTrue(rows.all { it.pendingFixtures == 1 && it.played == 0 })
    }

    @Test fun privateRsvpAndOfficialOnlyStandings() {
        assertEquals(AvailabilityRollup(1, 1, 0, 1), Scheduling.rollup("g", listOf("p1", "p2", "p3"), listOf(
            AvailabilityResponse("g", "p1", Availability.YES), AvailabilityResponse("g", "p2", Availability.NO))))
        val pending = fixture("pending").copy(resultStatus = ResultStatus.PENDING)
        val official = fixture("official").copy(resultStatus = ResultStatus.OFFICIAL, officialHome = 2, officialAway = 1)
        assertEquals(Standing("A", 1, 3, 2, 1, pendingFixtures = 1), Scheduling.standings(listOf("A", "B"), listOf(pending, official)).first())
        val provisional = Scheduling.standings(listOf("A", "B"), listOf(pending))
        assertTrue(provisional.all { it.played == 0 && it.pendingFixtures == 1 })
        assertEquals(0, Scheduling.standings(listOf("A", "B"), emptyList()).first().pendingFixtures)
    }
}
