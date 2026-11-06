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

    @Test fun rejectsUntrustedAndIncompleteBackupBeforeAnyPlatformWrite() {
        assertFailsWith<IllegalArgumentException> { BackupCodec.encode(BackupV1(version = 2)) }
        assertFailsWith<IllegalArgumentException> { BackupCodec.encode(BackupV1(teams = listOf(Team("a", "A", 1), Team("a", "B", 2)))) }
        assertFailsWith<IllegalArgumentException> { BackupCodec.encode(BackupV1(guardianContacts = listOf(GuardianContact("g", "missing", "X")))) }
        assertFailsWith<Exception> { BackupCodec.decode("{\"version\":2}") }
        assertFailsWith<Exception> { BackupCodec.decode("{\"version\":1,\"unknown\":true}") }
    }
}
