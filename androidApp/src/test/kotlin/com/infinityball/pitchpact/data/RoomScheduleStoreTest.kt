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
class RoomScheduleStoreTest {
    private lateinit var db: PitchPactDatabase
    private lateinit var teams: RoomTeamStore
    private lateinit var schedule: RoomScheduleStore
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PitchPactDatabase::class.java)
            .allowMainThreadQueries().build()
        teams = RoomTeamStore(db); schedule = RoomScheduleStore(db)
        teams.saveTeam(Team("a", "Falcons", 1)); teams.saveTeam(Team("b", "Comets", 1))
        teams.savePlayer(Player("p", "a", "Jo", 7, 1))
    }
    @After fun tearDown() = db.close()
    @Test fun scheduleAndPrivateRsvpRoundTripWithNamedConflict() {
        schedule.saveLocation(Location("field", "Field 1"))
        schedule.saveTournament(Tournament("cup", "Cup", listOf("a", "b"), TournamentFormat.ROUND_ROBIN))
        val game = Fixture("f", "First", "a", "b", 1_000_000, 90, "field", "cup", reminderMinutesBefore = 60)
        assertTrue(schedule.saveFixture(game).isEmpty())
        assertEquals(game, schedule.fixtures().single())
        assertEquals("Cup", schedule.tournaments().single().name)
        assertEquals("Field 1", schedule.locations().single().name)
        assertEquals("Team and field overlap with First", schedule.saveFixture(game.copy(id = "other", title = "Second")).single().reason)
        schedule.saveAvailability(AvailabilityResponse("f", "p", Availability.MAYBE))
        assertEquals(Availability.MAYBE, schedule.availability("f").single().choice)
        schedule.saveAvailability(AvailabilityResponse("f", "p", Availability.YES))
        assertEquals(1, schedule.availability("f").size)
        assertEquals(Availability.YES, schedule.availability("f").single().choice)
    }
    @Test fun guardedTeamDeletionIncludesFixturesAndCascadesPrivateRsvp() {
        val game = Fixture("f", "Game", "a", "b", 1_000_000, 90, null)
        schedule.saveFixture(game)
        schedule.saveAvailability(AvailabilityResponse("f", "p", Availability.YES))
        assertEquals(1, teams.deletionPreview("a").fixtureCount)
        assertFailsWith<ValidationException> { teams.deleteTeam("a") }
        teams.deleteTeam("a", requireEmpty = false)
        assertTrue(schedule.fixtures().isEmpty())
        assertTrue(schedule.availability("f").isEmpty())
    }
    @Test fun acceptedConsensusResultPersistsAndSurvivesUnchangedScheduleRefresh() {
        val shared = com.infinityball.pitchpact.dto.CoordinatedFixture(
            "m1", "LNK", "a", "b", 5_000_000, "Field 2", "Red", "Blue", "coordinated", setOf("a", "b"), 1)
        schedule.syncCoordinated("a", listOf(shared))
        val snapshot = com.infinityball.pitchpact.dto.ConsensusSnapshot(
            "m1", emptyList(), emptyList(), com.infinityball.pitchpact.dto.ScoreboardPayload("m1", 2, 1),
            emptyList(), setOf(Side.HOME, Side.AWAY), official = true)
        schedule.applyConsensus("LNK", "a", listOf(snapshot))
        val scored = schedule.fixtures().single { it.id == "coordinated:m1" }
        assertEquals(ResultStatus.OFFICIAL, scored.resultStatus)
        assertEquals(2, scored.officialHome); assertEquals(1, scored.officialAway)
        // Refresh with unchanged server revision must not wipe the accepted result.
        schedule.syncCoordinated("a", listOf(shared))
        assertEquals(scored, schedule.fixtures().single { it.id == "coordinated:m1" })
        // A pending-only board never overwrites an official result with a lower status silently:
        // local standings count only OFFICIAL rows.
        val standing = Scheduling.standings(listOf("a", "b"), schedule.fixtures()).single { it.teamId == "a" }
        assertEquals(1, standing.played); assertEquals(3, standing.points)
    }
}
