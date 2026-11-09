package com.infinityball.pitchpact.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackupCodecTest {
    @Test fun privateRecordsAndConsensusSurviveRoundTrip() {
        val goal = LedgerEvent.Event("goal", Side.HOME, 1, EventPayload.Goal(Side.HOME, 9, null, GoalType.OPEN_PLAY))
        val match = MatchDayState(
            matchId = "game", events = listOf(goal), queuedIds = listOf("goal"),
            decisions = listOf(StoredScoreDecision(ScoreChange("decision", "game", Side.HOME, "goal", ScoreChangeAction.ADD), ConsensusState.ACCEPTED, Side.AWAY)),
        )
        val original = BackupV1(
            teams = listOf(Team("ours", "Home", 1), Team("theirs", "Away", 2)),
            players = listOf(Player("player", "ours", "Alex", 9, createdAtEpochMillis = 1)),
            guardianContacts = listOf(GuardianContact("guardian", "player", "Parent", phone = "123")),
            fixtures = listOf(Fixture("game", "Final", "ours", "theirs", 1000, 60)),
            availability = listOf(AvailabilityResponse("game", "player", Availability.YES)),
            matches = listOf(match),
        )
        val text = BackupCodec.encode(original)
        assertEquals(original, BackupCodec.decode(text))
        assertEquals(1, BackupCodec.preview(text).consensusDecisions)
        assertEquals(1, BackupCodec.preview(text).ledgerEvents)
        assertEquals("1-0", BackupCodec.decode(text).matches.single().officialScore().render())
    }

    @Test fun seasonDeletionSelectsLocalGamesOnlyAndCountsPrivateLoss() {
        val fixtures = listOf(
            Fixture("inside", "Local", "a", "b", 100, 90),
            Fixture("before", "Prior", "a", "b", 99, 90),
            Fixture("after", "Next", "a", "b", 200, 90),
            Fixture("shared", "Coordinated", "a", "b", 150, 90, coordinatedLinkId = "link"),
        )
        val match = MatchDayState("inside", events = listOf(
            LedgerEvent.Event("goal", Side.HOME, 1, EventPayload.Goal(Side.HOME, 9, null, GoalType.OPEN_PLAY)),
        ))
        val backup = BackupV1(fixtures = fixtures, matches = listOf(match, MatchDayState("shared")),
            availability = listOf(AvailabilityResponse("inside", "p", Availability.YES),
                AvailabilityResponse("shared", "p", Availability.NO)))
        val preview = SeasonDeletion.preview(100, 200, backup)
        assertEquals(listOf("inside"), preview.fixtureIds)
        assertEquals(1, preview.coordinatedSkipped)
        assertEquals(1, preview.availabilityCount)
        assertEquals(1, preview.ledgerEvents)
        assertEquals(1, preview.matchCount)
        assertFailsWith<IllegalArgumentException> { SeasonDeletion.preview(200, 100, backup) }
    }

    @Test fun rejectsUntrustedAndIncompleteBackupBeforeAnyPlatformWrite() {
        assertFailsWith<IllegalArgumentException> { BackupCodec.encode(BackupV1(version = 2)) }
        assertFailsWith<IllegalArgumentException> { BackupCodec.encode(BackupV1(teams = listOf(Team("a", "A", 1), Team("a", "B", 2)))) }
        assertFailsWith<IllegalArgumentException> { BackupCodec.encode(BackupV1(guardianContacts = listOf(GuardianContact("g", "missing", "X")))) }
        assertFailsWith<Exception> { BackupCodec.decode("{\"version\":2}") }
        assertFailsWith<Exception> { BackupCodec.decode("{\"version\":1,\"unknown\":true}") }
    }
}
