package com.infinityball.pitchpact.store

import com.infinityball.pitchpact.domain.*

/** All reads and writes remain local. RSVP rows never participate in public DTO encoding. */
interface ScheduleStore {
    fun saveLocation(location: Location)
    fun locations(): List<Location>
    fun deleteLocation(id: String)
    fun saveTournament(tournament: Tournament)
    fun tournaments(): List<Tournament>
    fun deleteTournament(id: String)
    fun saveFixture(fixture: Fixture): List<ScheduleConflict>
    fun fixtures(): List<Fixture>
    fun deleteFixture(id: String)
    fun saveAvailability(response: AvailabilityResponse)
    fun availability(fixtureId: String): List<AvailabilityResponse>
}
