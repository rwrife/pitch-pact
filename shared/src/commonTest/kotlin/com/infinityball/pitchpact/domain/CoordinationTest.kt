package com.infinityball.pitchpact.domain

import com.infinityball.pitchpact.dto.CoordinatedFixture
import com.infinityball.pitchpact.dto.FixtureProposal
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
