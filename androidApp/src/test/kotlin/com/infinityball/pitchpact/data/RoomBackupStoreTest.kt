package com.infinityball.pitchpact.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.infinityball.pitchpact.domain.*
import kotlin.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomBackupStoreTest {
    private lateinit var db: PitchPactDatabase
    private lateinit var teams: RoomTeamStore
    private lateinit var schedule: RoomScheduleStore
    private lateinit var backup: RoomBackupStore

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PitchPactDatabase::class.java)
            .allowMainThreadQueries().build()
        teams = RoomTeamStore(db); schedule = RoomScheduleStore(db); backup = RoomBackupStore(db)
        teams.saveTeam(Team("t1", "Falcons", 100))
        teams.saveTeam(Team("rivals", "Rivals", 100))
        teams.savePlayer(Player("p1", "t1", "Jo", 7, 100))
        teams.saveGuardianContact(GuardianContact("g1", "p1", "Pat", "555", "pat@example.com", "Note"))
        teams.saveUniformRequirement(UniformRequirement("u1", "t1", "Rivals", Kit(KitColor.RED), Kit(KitColor.NAVY)))
        schedule.saveLocation(Location("loc1", "West Pitch"))
        schedule.saveTournament(Tournament("tourn1", "Summer Cup", listOf("t1", "rivals"), TournamentFormat.SINGLE_ELIMINATION))
        schedule.saveFixture(Fixture("f1", "Final", "t1", "rivals", 500, 90, "loc1", "tourn1"))
        schedule.saveAvailability(AvailabilityResponse("f1", "p1", Availability.YES))
        RoomMatchDayStore(db).save(MatchDayState("f1"))
    }

    @After fun tearDown() = db.close()

    @Test fun exportPreviewAndAtomicReplaceRoundTrip() {
        val payload = backup.export()
        val counts = backup.preview(payload)
        assertEquals(2, counts.teams)
        assertEquals(1, counts.players)
        assertEquals(1, counts.guardianContacts)
        assertEquals(1, counts.fixtures)
        assertEquals(1, counts.matches)

        // Mutate store
        teams.saveTeam(Team("t2", "Other", 200))
        assertEquals(3, teams.teams(includeArchived = true).size)

        // Replace
        backup.replace(payload)
        val restoredTeams = teams.teams(includeArchived = true)
        assertEquals(2, restoredTeams.size)
        assertEquals("Falcons", restoredTeams.single { it.id == "t1" }.name)
        assertEquals("Jo", teams.players("t1").single().name)
        assertEquals("Pat", teams.guardianContacts("p1").single().guardianName)
        assertEquals("West Pitch", schedule.locations().single().name)
        assertEquals(Availability.YES, schedule.availability("f1").single().choice)
        assertNotNull(RoomMatchDayStore(db).load("f1"))
    }

    @Test fun failedValidationDoesNotAlterExistingDatabase() {
        val invalidPayload = """{"version":1,"teams":[],"players":[{"id":"p","teamId":"missing","name":"A","jerseyNumber":1,"createdAtEpochMillis":1}],"guardianContacts":[],"uniforms":[],"locations":[],"tournaments":[],"fixtures":[],"availability":[],"matches":[]}"""
        assertFailsWith<IllegalArgumentException> {
            backup.replace(invalidPayload)
        }
        assertEquals(2, teams.teams(includeArchived = true).size)
        assertEquals("Falcons", teams.teams(includeArchived = true).single { it.id == "t1" }.name)
    }
}
