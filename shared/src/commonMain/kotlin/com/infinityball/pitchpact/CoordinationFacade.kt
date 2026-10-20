package com.infinityball.pitchpact

import com.infinityball.pitchpact.domain.Coordination
import com.infinityball.pitchpact.domain.Fixture
import com.infinityball.pitchpact.dto.ConsensusSnapshot
import com.infinityball.pitchpact.dto.CoordinatedFixture
import com.infinityball.pitchpact.dto.FixtureProposal
import com.infinityball.pitchpact.dto.PitchPactJson
import kotlinx.serialization.builtins.ListSerializer

/** Platform bridge: both native clients consume identical KMP wire + fixture rules. */
class CoordinationFacade {
    private val json = PitchPactJson.codec
    fun validateProposal(payload: String, now: Long): String = runCatching {
        Coordination.validate(json.decodeFromString(FixtureProposal.serializer(), payload), now)
        "OK"
    }.getOrElse { "ERROR:${it.message}" }
    fun scheduleVisible(fixtureJson: String, teamId: String): Boolean =
        Coordination.visible(json.decodeFromString(CoordinatedFixture.serializer(), fixtureJson), teamId)
    /** Merge the server's coordinated schedule into the local one; returns ScheduleMerge JSON. */
    fun mergeSchedule(scheduleJson: String, teamId: String, existingFixturesJson: String): String =
        Coordination.mergeSchedule(
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(CoordinatedFixture.serializer()), scheduleJson),
            teamId,
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Fixture.serializer()), existingFixturesJson),
        ).toJson()
    fun applyConsensus(fixturesJson: String, linkId: String, linkHomeTeamId: String, snapshotsJson: String): String =
        json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Fixture.serializer()), Coordination.applyResults(
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Fixture.serializer()), fixturesJson),
            linkId,
            linkHomeTeamId,
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ConsensusSnapshot.serializer()), snapshotsJson),
        ))
}
