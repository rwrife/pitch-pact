package com.infinityball.pitchpact.data

import androidx.room.withTransaction
import com.infinityball.pitchpact.domain.GuardianContact
import com.infinityball.pitchpact.domain.Kit
import com.infinityball.pitchpact.domain.KitColor
import com.infinityball.pitchpact.domain.Player
import com.infinityball.pitchpact.domain.Team
import com.infinityball.pitchpact.domain.TeamDeletionPreview
import com.infinityball.pitchpact.domain.UniformRequirement
import com.infinityball.pitchpact.domain.Validate
import com.infinityball.pitchpact.domain.ValidationException
import com.infinityball.pitchpact.store.TeamStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Room-backed [TeamStore] (Android side of the M2 dual-store contract).
 *
 * All rules live in `shared` ([Validate], [TeamDeletionPreview]); this class
 * only maps rows. Callers run it on the UI thread via runBlocking for now
 * (single-threaded-by-contract; M3+ may move reads onto dispatchers).
 */
class RoomTeamStore(private val db: PitchPactDatabase) : TeamStore {

    private val dao get() = db.teamDao()
    private val json = Json

    override fun schemaVersion(): Long = db.openHelper.readableDatabase.version.toLong()

    // --- Teams ---

    override fun saveTeam(team: Team) = runBlocking {
        db.withTransaction {
            Validate.teamName(team.name)
            dao.upsertTeam(team.toRow())
        }
    }

    override fun team(id: String): Team? = dao.team(id)?.toDomain()

    override fun teams(includeArchived: Boolean): List<Team> =
        dao.teams(includeArchived).map { it.toDomain() }

    override fun archiveTeam(id: String, archived: Boolean, atEpochMillis: Long) = runBlocking {
        db.withTransaction { dao.setArchived(id, if (archived) atEpochMillis else null) }
    }

    override fun deletionPreview(id: String): TeamDeletionPreview =
        TeamDeletionPreview(
            teamId = id,
            playerCount = dao.playerCount(id),
            uniformRequirementCount = dao.uniformCount(id),
            guardianContactCount = dao.guardianCountForTeam(id),
            fixtureCount = RoomScheduleStore(db).fixtures().count { it.homeTeamId == id || it.awayTeamId == id },
        )

    override fun deleteTeam(id: String, requireEmpty: Boolean) = runBlocking {
        db.withTransaction {
            val preview = deletionPreview(id)
            if (requireEmpty && preview.blockingCount > 0) {
                throw ValidationException(
                    "team.not_empty",
                    "Team still holds ${preview.playerCount} players, ${preview.uniformRequirementCount} uniforms, ${preview.guardianContactCount} guardian contacts",
                )
            }
            RoomScheduleStore(db).fixtures().filter { it.homeTeamId == id || it.awayTeamId == id }
                .forEach { db.scheduleDao().deleteAvailability(it.id); db.scheduleDao().deleteFixture(it.id) }
            dao.players(id).forEach { db.scheduleDao().deletePlayerAvailability(it.id) }
            dao.deleteGuardiansForAllPlayers(id)
            dao.players(id).forEach { dao.deletePlayer(it.id) }
            dao.uniforms(id).forEach { dao.deleteUniform(it.id) }
            dao.deleteTeam(id)
        }
    }

    // --- Players ---

    override fun savePlayer(player: Player) = runBlocking {
        db.withTransaction {
            Validate.playerName(player.name)
            Validate.jerseyUnique(dao.players(player.teamId).map { it.toDomain() }, player)
            dao.upsertPlayer(player.toRow())
        }
    }

    override fun player(id: String): Player? = dao.player(id)?.toDomain()

    override fun players(teamId: String): List<Player> =
        dao.players(teamId).map { it.toDomain() }

    override fun deletePlayer(id: String) = runBlocking {
        db.withTransaction {
            db.scheduleDao().deletePlayerAvailability(id)
            dao.deleteGuardiansForPlayer(id)
            dao.deletePlayer(id)
        }
    }

    // --- Uniforms ---

    override fun saveUniformRequirement(requirement: UniformRequirement) = runBlocking {
        db.withTransaction {
            Validate.opponentName(requirement.opponentName)
            dao.upsertUniform(
                requirement.copy(
                    equipmentChecklist = Validate.normalizedChecklist(requirement.equipmentChecklist),
                ).toRow(),
            )
        }
    }

    override fun uniformRequirements(teamId: String): List<UniformRequirement> =
        dao.uniforms(teamId).map { it.toDomain() }

    override fun deleteUniformRequirement(id: String) = dao.deleteUniform(id)

    // --- Guardian contacts ---

    override fun saveGuardianContact(contact: GuardianContact) = runBlocking {
        db.withTransaction {
            Validate.guardianName(contact.guardianName)
            Validate.guardianReachable(contact)
            dao.upsertGuardian(contact.toRow())
        }
    }

    override fun guardianContacts(playerId: String): List<GuardianContact> =
        dao.guardians(playerId).map { it.toDomain() }

    override fun guardianContactsForTeam(teamId: String): List<GuardianContact> =
        dao.guardiansForTeam(teamId).map { it.toDomain() }

    override fun deleteGuardianContact(id: String) = dao.deleteGuardian(id)

    // --- Mapping (row <-> shared DTO; no business rules here) ---

    private fun Team.toRow() = TeamRow(id, name, createdAtEpochMillis, archivedAtEpochMillis)
    private fun TeamRow.toDomain() = Team(id, name, createdAt, archivedAt)

    private fun Player.toRow() = PlayerRow(id, teamId, name, jerseyNumber, createdAtEpochMillis)
    private fun PlayerRow.toDomain() = Player(id, teamId, name, jerseyNumber, createdAt)

    private fun UniformRequirement.toRow() = UniformRow(
        id = id, teamId = teamId, opponentName = opponentName,
        homePrimary = homeKit.primary.name, homeAlternate = homeKit.alternate?.name,
        awayPrimary = awayKit.primary.name, awayAlternate = awayKit.alternate?.name,
        keeperPrimary = keeperKit?.primary?.name, keeperAlternate = keeperKit?.alternate?.name,
        equipmentJson = json.encodeToString(ListSerializer(String.serializer()), equipmentChecklist),
        notes = notes,
    )

    private fun UniformRow.toDomain() = UniformRequirement(
        id = id, teamId = teamId, opponentName = opponentName,
        homeKit = Kit(KitColor.valueOf(homePrimary), homeAlternate?.let(KitColor::valueOf)),
        awayKit = Kit(KitColor.valueOf(awayPrimary), awayAlternate?.let(KitColor::valueOf)),
        keeperKit = keeperPrimary?.let { Kit(KitColor.valueOf(it), keeperAlternate?.let(KitColor::valueOf)) },
        equipmentChecklist = json.decodeFromString(ListSerializer(String.serializer()), equipmentJson),
        notes = notes,
    )

    private fun GuardianContact.toRow() =
        GuardianRow(id, playerId, guardianName, phone, email, notes)

    private fun GuardianRow.toDomain() =
        GuardianContact(id, playerId, guardianName, phone, email, notes)

    companion object {
        const val DB_NAME = "pitchpact.db"
    }
}

/** Extra cascades the DAO needs but the generic @Dao can't derive. */
private fun TeamDao.deleteGuardiansForAllPlayers(teamId: String) {
    players(teamId).forEach { deleteGuardiansForPlayer(it.id) }
}
