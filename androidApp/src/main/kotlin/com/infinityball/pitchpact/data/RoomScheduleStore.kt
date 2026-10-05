package com.infinityball.pitchpact.data

import androidx.room.withTransaction
import com.infinityball.pitchpact.domain.*
import com.infinityball.pitchpact.store.ScheduleStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class RoomScheduleStore(private val db: PitchPactDatabase) : ScheduleStore {
    private val dao get() = db.scheduleDao()
    private val json = Json { encodeDefaults = true }

    override fun saveLocation(location: Location) {
        require(location.name.isNotBlank()) { "Location name required" }
        dao.saveLocation(LocationRow(location.id, json.encodeToString(Location.serializer(), location)))
    }
    override fun locations() = dao.locations().map { json.decodeFromString(Location.serializer(), it.payload) }
    override fun deleteLocation(id: String) = dao.deleteLocation(id)
    override fun saveTournament(tournament: Tournament) {
        require(tournament.name.isNotBlank()) { "Tournament name required" }
        dao.saveTournament(TournamentRow(tournament.id, json.encodeToString(Tournament.serializer(), tournament)))
    }
    override fun tournaments() = dao.tournaments().map { json.decodeFromString(Tournament.serializer(), it.payload) }
    override fun deleteTournament(id: String) = dao.deleteTournament(id)
    override fun saveFixture(fixture: Fixture): List<ScheduleConflict> = runBlocking {
        db.withTransaction {
            Scheduling.validate(fixture)
            val conflicts = Scheduling.conflicts(fixture, fixtures())
            dao.saveFixture(FixtureRow(fixture.id, json.encodeToString(Fixture.serializer(), fixture)))
            conflicts // warnings returned, never silently block
        }
    }
    override fun fixtures() = dao.fixtures().map { json.decodeFromString(Fixture.serializer(), it.payload) }
        .sortedBy { it.startEpochMillis }
    override fun deleteFixture(id: String) = runBlocking {
        db.withTransaction { dao.deleteAvailability(id); dao.deleteFixture(id) }
    }
    override fun saveAvailability(response: AvailabilityResponse) {
        require(db.teamDao().player(response.playerId) != null) { "Player not on roster" }
        require(fixtures().any { it.id == response.fixtureId }) { "Fixture not found" }
        dao.saveAvailability(AvailabilityRow(response.fixtureId, response.playerId, response.choice.name))
    }
    override fun availability(fixtureId: String) = dao.availability(fixtureId).map {
        AvailabilityResponse(it.fixtureId, it.playerId, Availability.valueOf(it.choice))
    }
}
