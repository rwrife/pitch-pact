package com.infinityball.pitchpact.domain

import com.infinityball.pitchpact.dto.CoordinatedFixture
import com.infinityball.pitchpact.dto.FixtureProposal
import com.infinityball.pitchpact.dto.ConsensusSnapshot
import com.infinityball.pitchpact.dto.ScoreboardPayload
import com.infinityball.pitchpact.dto.PendingChangeView
import kotlin.test.*

class CoordinationTest {
    private val now = 1_000L
    private val day = 86_400_000L
    private fun proposal(start: Long = now + day) = FixtureProposal(start, "Field 3", "Red", "Blue", "idempotency-key-0123456789")
    private fun fixture(state: String, confirmed: Set<String>, rev: Int = 1) = CoordinatedFixture(
        "f1", "LNK-1", "A", "B", now + day, "Field 3", "Red", "Blue", state, confirmed, rev)

    @Test fun validationRejectsPastBlankAndOversized() {
        Coordination.validate(proposal(), now)
        assertFailsWith<IllegalArgumentException> { Coordination.validate(proposal(now - 1), now) }
        assertFailsWith<IllegalArgumentException> { Coordination.validate(proposal(now + 400 * day), now) }
        assertFailsWith<IllegalArgumentException> { Coordination.validate(proposal().copy(location = " "), now) }
        assertFailsWith<IllegalArgumentException> { Coordination.validate(proposal().copy(homeUniform = "x".repeat(101)), now) }
    }

    @Test fun confirmBeforeShowRequiresBothSides() {
        val proposed = fixture("proposed", setOf("A"))
        assertFalse(Coordination.visible(proposed, "A"))
        assertFalse(Coordination.visible(proposed, "B"))
        val confirmed = Coordination.confirm(proposed, "B")
        assertEquals("coordinated", confirmed.state)
        assertTrue(Coordination.visible(confirmed, "A") && Coordination.visible(confirmed, "B"))
        assertFalse(Coordination.visible(confirmed, "C"))
        assertFailsWith<IllegalArgumentException> { Coordination.confirm(proposed, "C") }
    }

    @Test fun reviseVoidsBothConfirmationsAndBumpsRevision() {
        val revised = Coordination.revise(fixture("coordinated", setOf("A", "B")), proposal(now + 2 * day), now)
        assertEquals("proposed", revised.state)
        assertEquals(emptySet(), revised.confirmedBy)
        assertEquals(2, revised.revision)
        assertFalse(Coordination.visible(revised, "A"))
    }

    @Test fun projectionCarriesCoordinatedProvenanceAndOnlyAcceptedFixtures() {
        val accepted = fixture("coordinated", setOf("A", "B"))
        val local = Coordination.scheduleFixture(accepted, "A")
        assertEquals("LNK-1", local.coordinatedLinkId)
        assertEquals(1, local.coordinatedRevision)
        assertEquals("coordinated:f1", local.id)
        assertFailsWith<IllegalArgumentException> { Coordination.scheduleFixture(fixture("proposed", setOf("A")), "A") }
    }

    @Test fun localEditsOfCoordinatedFixturesAreRefusedButNonScheduleFieldsStayEditable() {
        val local = Coordination.scheduleFixture(fixture("coordinated", setOf("A", "B")), "A")
        val moved = local.copy(startEpochMillis = local.startEpochMillis + 1)
        val ex = assertFailsWith<ValidationException> { Scheduling.validateEdit(local, moved) }
        assertEquals("fixture.coordinated.locked", ex.code)
        Scheduling.validateEdit(local, local.copy(reminderMinutesBefore = 60)) // reminders are local-only
        Scheduling.validateEdit(null, moved) // brand-new local fixtures unaffected
    }

    @Test fun officialResultProjectsToBothLinkPartiesWithHomeFromTheLink() {
        val snapshot = ConsensusSnapshot(
            "f1", emptyList(), emptyList(), ScoreboardPayload("f1", 2, 1),
            listOf(PendingChangeView("c1", Side.AWAY, ConsensusState.PENDING)),
            setOf(Side.HOME), official = false,
        )
        val coordinated = fixture("coordinated", setOf("A", "B"))
        // First schedule projection maps A to HOME.
        val home = Coordination.applyResult(Coordination.scheduleFixture(coordinated, "A"), "LNK-1", "A", snapshot)
        assertEquals(2, home.officialHome)
        assertEquals(1, home.officialAway)
        assertEquals(ResultStatus.PENDING, home.resultStatus)
        // If B proposes the fixture, its fixture home is B, but link HOME is A.
        val bHome = coordinated.copy(homeTeamId = "B", awayTeamId = "A")
        val away = Coordination.applyResult(Coordination.scheduleFixture(bHome, "B"), "LNK-1", "A", snapshot)
        assertEquals(1, away.officialHome)
        assertEquals(2, away.officialAway)
        // Server team ids do not determine local fixture storage; unrelated fixtures
        // with a colliding suffix get no result rather than an injected scoreboard.
        val unrelated = Fixture("coordinated:other-f1", "Scrimmage", "A", "B", now + day, 90)
        assertEquals(unrelated, Coordination.applyResult(unrelated, "LNK-1", "A", snapshot))
    }

    @Test fun emptyBoardNeverBecomesAResultAndPendingNeverBecomesOfficial() {
        val quiet = ConsensusSnapshot("f1", emptyList(), emptyList(), ScoreboardPayload("f1", 0, 0), emptyList(), emptySet(), official = false)
        val local = Coordination.scheduleFixture(fixture("coordinated", setOf("A", "B")), "A")
        assertEquals(local, Coordination.applyResult(local, "LNK-1", "A", quiet))
        assertEquals(local, Coordination.applyResult(local, "OTHER-LINK", "A", quiet.copy(finalWhistles = setOf(Side.HOME))))
        val oneWhistle = Coordination.applyResult(local, "LNK-1", "A", quiet.copy(finalWhistles = setOf(Side.HOME)))
        assertEquals(ResultStatus.PENDING, oneWhistle.resultStatus)
    }

    @Test fun finalWhistleProjectionMarksScheduledResultOfficial() {
        val official = ConsensusSnapshot(
            "f1", emptyList(), emptyList(), ScoreboardPayload("f1", 3, 0), emptyList(),
            setOf(Side.HOME, Side.AWAY), official = true,
        )
        val local = Coordination.applyResult(Coordination.scheduleFixture(fixture("coordinated", setOf("A", "B")), "A"), "LNK-1", "A", official)
        assertEquals(ResultStatus.OFFICIAL, local.resultStatus)
        assertEquals(3, local.officialHome)
        assertEquals(0, local.officialAway)
    }

    @Test fun mergePreservesConsensusResultOnlyOnUnchangedCurrentRevision() {
        val coordinated = fixture("coordinated", setOf("A", "B"))
        val local = Coordination.applyResult(Coordination.scheduleFixture(coordinated, "A"), "LNK-1", "A", ConsensusSnapshot(
            "f1", emptyList(), emptyList(), ScoreboardPayload("f1", 2, 1), emptyList(),
            setOf(Side.HOME, Side.AWAY), official = true,
        ))
        val unchanged = Coordination.mergeSchedule(listOf(coordinated), "A", listOf(local))
        assertEquals(local, unchanged.fixtures.single())
        val revised = Coordination.mergeSchedule(listOf(fixture("coordinated", setOf("A", "B"), rev = 2)), "A", listOf(local))
        assertEquals(ResultStatus.UNSCORED, revised.fixtures.single().resultStatus)
        assertNull(revised.fixtures.single().officialHome)
    }

    @Test fun mergeAppliesNewerRevisionsAndDropsRevokedOrUnconfirmedProjections() {
        val existing = listOf(Coordination.scheduleFixture(fixture("coordinated", setOf("A", "B")), "A"))
        val revisedPending = fixture("proposed", setOf("A"), rev = 2)
        val dropped = Coordination.mergeSchedule(listOf(revisedPending), "A", existing)
        assertEquals(listOf("coordinated:f1"), dropped.removedFixtureIds)
        assertTrue(dropped.fixtures.isEmpty())
        val reconfirmed = Coordination.mergeSchedule(listOf(fixture("coordinated", setOf("A", "B"), rev = 2)), "A", existing)
        assertEquals(2, reconfirmed.fixtures.single().coordinatedRevision)
        assertTrue(Coordination.shouldSync(existing.single(), reconfirmed.fixtures.single()))
        assertFalse(Coordination.shouldSync(reconfirmed.fixtures.single(), existing.single()))
        val plainLocal = Fixture("local-1", "Scrimmage", "A", "C", 5L, 60)
        assertTrue(Coordination.mergeSchedule(emptyList(), "A", listOf(plainLocal)).removedFixtureIds.isEmpty())
    }
}
