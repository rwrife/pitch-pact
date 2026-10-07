package com.infinityball.pitchpact

import com.infinityball.pitchpact.domain.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Swift bridge: native SwiftUI asks the shared module for scheduling decisions. */
class SchedulingFacade {
    private val json = Json { encodeDefaults = true }
    fun validateFixture(fixtureJson: String): String = runCatching {
        val fixture = json.decodeFromString(Fixture.serializer(), fixtureJson)
        Scheduling.validate(fixture)
        "OK"
    }.getOrElse { "ERROR:${it.message}" }
    fun validateEdit(previousJson: String, nextJson: String): String = runCatching {
        val prior = if (previousJson == "null") null else json.decodeFromString(Fixture.serializer(), previousJson)
        Scheduling.validateEdit(prior, json.decodeFromString(Fixture.serializer(), nextJson))
        "OK"
    }.getOrElse { "ERROR:${it.message}" }
    fun conflicts(fixtureJson: String, existingJson: String): String = json.encodeToString(
        ListSerializer(ScheduleConflict.serializer()), Scheduling.conflicts(
            json.decodeFromString(Fixture.serializer(), fixtureJson),
            json.decodeFromString(ListSerializer(Fixture.serializer()), existingJson),
        ),
    )
    fun draft(teamIds: List<String>, format: String): String = runCatching { json.encodeToString(
        ListSerializer(DraftPairing.serializer()), Scheduling.draft(teamIds, TournamentFormat.valueOf(format)),
    ) }.getOrDefault("[]")
    fun reminderEpochMillis(fixtureJson: String): Long? = Scheduling.reminderEpochMillis(
        json.decodeFromString(Fixture.serializer(), fixtureJson),
    )
    fun reminderTimestamp(fixtureJson: String): Long = reminderEpochMillis(fixtureJson) ?: -1L
    fun rollup(fixtureId: String, playerIds: List<String>, responsesJson: String): String = json.encodeToString(
        AvailabilityRollup.serializer(), Scheduling.rollup(fixtureId,
            playerIds, json.decodeFromString(ListSerializer(AvailabilityResponse.serializer()), responsesJson),
        ),
    )
    fun standings(teamIds: List<String>, fixturesJson: String): String = json.encodeToString(
        ListSerializer(Standing.serializer()), Scheduling.standings(
            teamIds, json.decodeFromString(ListSerializer(Fixture.serializer()), fixturesJson),
        ),
    )
}
