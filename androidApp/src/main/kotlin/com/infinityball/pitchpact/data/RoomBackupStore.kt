package com.infinityball.pitchpact.data

import com.infinityball.pitchpact.domain.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Private local backup; caller must show preview before calling replace. */
class RoomBackupStore(private val db: PitchPactDatabase) {
    private val json = Json { encodeDefaults = true }
    private val checklist = ListSerializer(String.serializer())
    private val teams get() = db.teamDao()
    private val schedule get() = db.scheduleDao()
    private val matches get() = db.matchDayDao()

    fun export(): String {
        val teamStore = RoomTeamStore(db)
        val scheduleStore = RoomScheduleStore(db)
        val allTeams = teamStore.teams(includeArchived = true)
        val allPlayers = allTeams.flatMap { teamStore.players(it.id) }
        return BackupCodec.encode(BackupV1(
            teams = allTeams,
            players = allPlayers,
            guardianContacts = allPlayers.flatMap { teamStore.guardianContacts(it.id) },
            uniforms = allTeams.flatMap { teamStore.uniformRequirements(it.id) },
            locations = scheduleStore.locations(),
            tournaments = scheduleStore.tournaments(),
            fixtures = scheduleStore.fixtures(),
            availability = schedule.allAvailability().map { AvailabilityResponse(it.fixtureId, it.playerId, Availability.valueOf(it.choice)) },
            matches = matches.all().map { MatchDayCodec.decode(it.payload) },
        ))
    }

    fun preview(data: String): BackupCounts = BackupCodec.preview(data)

    /** Entire replacement rolls back on any failure. Cancel must never invoke this method. */
    fun replace(data: String) {
        val b = BackupCodec.decode(data)
        db.runInTransaction {
            matches.clear()
            schedule.clearAvailability()
            schedule.clearFixtures()
            schedule.clearTournaments()
            schedule.clearLocations()
            teams.clearGuardians()
            teams.clearUniforms()
            teams.clearPlayers()
            teams.clearTeams()
            b.teams.forEach { teams.upsertTeam(TeamRow(it.id, it.name, it.createdAtEpochMillis, it.archivedAtEpochMillis)) }
            b.players.forEach { teams.upsertPlayer(PlayerRow(it.id, it.teamId, it.name, it.jerseyNumber, it.createdAtEpochMillis)) }
            b.guardianContacts.forEach { teams.upsertGuardian(GuardianRow(it.id, it.playerId, it.guardianName, it.phone, it.email, it.notes)) }
            b.uniforms.forEach { u -> teams.upsertUniform(UniformRow(
                u.id, u.teamId, u.opponentName, u.homeKit.primary.name, u.homeKit.alternate?.name,
                u.awayKit.primary.name, u.awayKit.alternate?.name, u.keeperKit?.primary?.name,
                u.keeperKit?.alternate?.name, json.encodeToString(checklist, u.equipmentChecklist), u.notes,
            )) }
            b.locations.forEach { schedule.saveLocation(LocationRow(it.id, json.encodeToString(Location.serializer(), it))) }
            b.tournaments.forEach { schedule.saveTournament(TournamentRow(it.id, json.encodeToString(Tournament.serializer(), it))) }
            b.fixtures.forEach { schedule.saveFixture(FixtureRow(it.id, json.encodeToString(Fixture.serializer(), it))) }
            b.availability.forEach { schedule.saveAvailability(AvailabilityRow(it.fixtureId, it.playerId, it.choice.name)) }
            b.matches.forEach { matches.save(MatchDayRow(it.matchId, MatchDayCodec.encode(it))) }
        }
    }
}
