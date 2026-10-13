package com.infinityball.pitchpact.domain

import com.infinityball.pitchpact.dto.CoordinatedFixture
import com.infinityball.pitchpact.dto.FixtureProposal

/** Pure rules used by both native shells and server. Never expose private team data. */
object Coordination {
    fun validate(proposal: FixtureProposal, now: Long) {
        require(proposal.startEpochMillis > now && proposal.startEpochMillis < now + 366L * 86_400_000L)
        require(proposal.location.isNotBlank() && proposal.location.length <= 200)
        require(proposal.homeUniform.isNotBlank() && proposal.homeUniform.length <= 100)
        require(proposal.awayUniform.isNotBlank() && proposal.awayUniform.length <= 100)
        require(proposal.idempotencyKey.length in 16..128)
    }
    fun confirm(fixture: CoordinatedFixture, teamId: String): CoordinatedFixture {
        require(fixture.state == "proposed" && teamId in setOf(fixture.homeTeamId, fixture.awayTeamId))
        val confirmed = fixture.confirmedBy + teamId
        return fixture.copy(confirmedBy = confirmed, state = if (confirmed.size == 2) "coordinated" else "proposed")
    }
    fun visible(fixture: CoordinatedFixture, teamId: String): Boolean =
        fixture.state == "coordinated" && teamId in setOf(fixture.homeTeamId, fixture.awayTeamId)
    /** Deterministic local schedule projection: only accepted fixtures materialize. */
    fun scheduleFixture(fixture: CoordinatedFixture, teamId: String): Fixture {
        require(visible(fixture, teamId)) { "Only both-side coordinated fixtures belong in local schedule" }
        return Fixture(
            id = "coordinated:${fixture.id}", title = "Shared match · ${fixture.homeUniform} / ${fixture.awayUniform}",
            homeTeamId = fixture.homeTeamId, awayTeamId = fixture.awayTeamId,
            startEpochMillis = fixture.startEpochMillis, durationMinutes = 90,
            locationId = "coordinated-location:${fixture.id}",
            coordinatedLinkId = fixture.linkId, coordinatedRevision = fixture.revision,
        )
    }
    fun location(fixture: CoordinatedFixture): Location =
        Location("coordinated-location:${fixture.id}", fixture.location)
    /** Server revisions are the only path to mutate coordinated local fixture fields. */
    fun shouldSync(previous: Fixture?, next: Fixture): Boolean =
        previous == null || (previous.coordinatedLinkId == next.coordinatedLinkId &&
            (previous.coordinatedRevision ?: 0) < (next.coordinatedRevision ?: 0))
    /**
     * Merge result the platform stores apply verbatim: refreshed/added projections plus the
     * ids of coordinated fixtures that vanished (link revoked/expired) and must drop out of
     * the schedule. Local (non-coordinated) fixtures are never touched.
     */
    data class ScheduleMerge(
        val locations: List<Location>, val fixtures: List<Fixture>, val removedFixtureIds: List<String>,
    ) {
        @kotlinx.serialization.Serializable
        private data class Wire(val locations: List<Location>, val fixtures: List<Fixture>, val removedFixtureIds: List<String>)
        fun toJson(): String = com.infinityball.pitchpact.dto.PitchPactJson.codec.encodeToString(
            Wire.serializer(), Wire(locations, fixtures, removedFixtureIds))
        companion object {
            fun fromJson(text: String): ScheduleMerge {
                val w = com.infinityball.pitchpact.dto.PitchPactJson.codec.decodeFromString(Wire.serializer(), text)
                return ScheduleMerge(w.locations, w.fixtures, w.removedFixtureIds)
            }
        }
    }
    fun mergeSchedule(schedule: List<CoordinatedFixture>, teamId: String, existing: List<Fixture>): ScheduleMerge {
        val projections = schedule.filter { visible(it, teamId) }
        val fixtures = projections.map { scheduleFixture(it, teamId) }
        val keep = fixtures.map { it.id }.toSet()
        // A coordinated fixture disappears once unconfirmed (revoked link or pending revision):
        // drop any stale local projection. Non-coordinated fixtures are never touched.
        val stale = existing.filter { it.coordinatedLinkId != null && it.id !in keep }.map { it.id }
        return ScheduleMerge(projections.map(::location), fixtures, stale)
    }
    /** Any material edit invalidates BOTH confirmations; never silently overwrite local schedules. */
    fun revise(fixture: CoordinatedFixture, proposal: FixtureProposal, now: Long): CoordinatedFixture {
        require(fixture.state != "revoked")
        validate(proposal, now)
        return fixture.copy(startEpochMillis = proposal.startEpochMillis, location = proposal.location,
            homeUniform = proposal.homeUniform, awayUniform = proposal.awayUniform,
            confirmedBy = emptySet(), state = "proposed", revision = fixture.revision + 1)
    }
}
