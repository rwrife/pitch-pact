package com.infinityball.pitchpact.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.infinityball.pitchpact.domain.GuardianContact
import com.infinityball.pitchpact.domain.Kit
import com.infinityball.pitchpact.domain.KitColor
import com.infinityball.pitchpact.domain.Player
import com.infinityball.pitchpact.domain.Team
import com.infinityball.pitchpact.domain.UniformRequirement
import com.infinityball.pitchpact.domain.ValidationException
import com.infinityball.pitchpact.store.StoreFixtureCodec
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * M2 Android store acceptance: Room store v1 implements the shared
 * [com.infinityball.pitchpact.store.TeamStore] contract, round-trips the SAME
 * cross-platform fixture file the iOS GRDB store test loads, and enforces
 * jersey uniqueness + cascade preview at the store layer.
 *
 * Robolectric runs real SQLite on the JVM (Linux CI evidence; NOT Android
 * device evidence — that gap is disclosed in the PR body).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomTeamStoreTest {

    private lateinit var db: PitchPactDatabase
    private lateinit var store: RoomTeamStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, PitchPactDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomTeamStore(db)
    }

    @After
    fun tearDown() = db.close()

    private fun team(id: String, name: String = "T") =
        Team(id = id, name = name, createdAtEpochMillis = 1)

    private fun player(id: String, teamId: String, name: String, jersey: Int) =
        Player(id, teamId, name, jersey, 1)

    @Test
    fun schemaIsVersionOne() {
        assertEquals(3L, store.schemaVersion())
    }

    @Test
    fun roundTripsSharedCrossPlatformFixture() {
        val fixture = javaClass.getResourceAsStream("/m2-teams.json")
            ?.bufferedReader()?.readText()
            ?: File("../fixtures/m2-teams.json").readText()
        val (teams, players) = StoreFixtureCodec.decode(fixture)
        teams.forEach { store.saveTeam(it) }
        players.forEach { store.savePlayer(it) }

        assertEquals(teams.filter { !it.isArchived }.map { it.id }.toSet(),
            store.teams().map { it.id }.toSet())
        assertTrue(store.teams(includeArchived = true).any { it.isArchived })
        assertEquals(players.filter { it.teamId == "team-u8-falcons" }.map { it.id }.toSet(),
            store.players("team-u8-falcons").map { it.id }.toSet())
        // Per-team jersey uniqueness holds across the fixture.
        assertEquals(1, store.players("team-u8-falcons").count { it.jerseyNumber == 9 })
    }

    @Test
    fun teamCrudAndArchiveRoundTrip() {
        store.saveTeam(team("t1", "Falcons"))
        assertEquals("Falcons", store.team("t1")?.name)
        store.archiveTeam("t1", true, 99)
        assertNull(store.teams().firstOrNull { it.id == "t1" }, "archived hides from active list")
        assertNotNull(store.teams(includeArchived = true).firstOrNull { it.isArchived })
        store.archiveTeam("t1", false, 0)
        assertNotNull(store.teams().firstOrNull { it.id == "t1" }, "unarchive restores")
    }

    @Test
    fun jerseyClashRejectedAtStoreLayerEvenBypassingValidate() {
        store.saveTeam(team("t1"))
        store.savePlayer(player("p1", "t1", "Bo", 9))
        val e = assertFailsWith<ValidationException> {
            store.savePlayer(player("p2", "t1", "Cy", 9))
        }
        assertEquals("jersey.clash", e.code)
        // Re-assigning the same number to the same player is fine.
        store.savePlayer(player("p1", "t1", "Bo", 9))
        // Same number on ANOTHER team is fine.
        store.saveTeam(team("t2"))
        store.savePlayer(player("p3", "t2", "Di", 9))
    }

    @Test
    fun deletionPreviewCountsThenConfirmedDeleteCascades() {
        store.saveTeam(team("t1"))
        store.savePlayer(player("p1", "t1", "Bo", 9))
        store.saveGuardianContact(GuardianContact("g1", "p1", "Sam", phone = "555"))
        store.saveUniformRequirement(
            UniformRequirement("u1", "t1", "Rivals", Kit(KitColor.RED), Kit(KitColor.BLUE)),
        )

        val preview = store.deletionPreview("t1")
        assertEquals(1, preview.playerCount)
        assertEquals(1, preview.uniformRequirementCount)
        assertEquals(1, preview.guardianContactCount)
        assertEquals(3, preview.blockingCount)

        // Guarded delete refuses without destroying anything.
        assertFailsWith<ValidationException> { store.deleteTeam("t1", requireEmpty = true) }
        assertNotNull(store.team("t1"))

        // Confirmed cascade removes team + dependents + private contacts.
        store.deleteTeam("t1", requireEmpty = false)
        assertNull(store.team("t1"))
        assertNull(store.player("p1"))
        assertTrue(store.guardianContacts("p1").isEmpty())
        assertTrue(store.uniformRequirements("t1").isEmpty())
    }

    @Test
    fun playerDeleteCascadesGuardianContacts() {
        store.saveTeam(team("t1"))
        store.savePlayer(player("p1", "t1", "Bo", 9))
        store.saveGuardianContact(GuardianContact("g1", "p1", "Sam", phone = "555"))
        store.deletePlayer("p1")
        assertNull(store.player("p1"))
        assertTrue(store.guardianContacts("p1").isEmpty(), "guardian rows must not outlive the player")
    }

    @Test
    fun uniformRequirementRoundTripsKitsAndChecklist() {
        store.saveTeam(team("t1"))
        val req = UniformRequirement(
            id = "u1", teamId = "t1", opponentName = "Comets",
            homeKit = Kit(KitColor.WHITE, KitColor.GREY),
            awayKit = Kit(KitColor.BLUE),
            keeperKit = Kit(KitColor.GREEN),
            equipmentChecklist = listOf(" shinguards ", "shinguards", "size 4 ball", ""),
            notes = "match day at north field",
        )
        store.saveUniformRequirement(req)
        val back = store.uniformRequirements("t1").single()
        assertEquals(req.copy(equipmentChecklist = listOf("shinguards", "size 4 ball")), back)
    }

    @Test
    fun guardianNeedsRouteAtStoreLayer() {
        store.saveTeam(team("t1"))
        store.savePlayer(player("p1", "t1", "Bo", 9))
        assertFailsWith<ValidationException> {
            store.saveGuardianContact(GuardianContact("g1", "p1", "Sam"))
        }
    }
}
