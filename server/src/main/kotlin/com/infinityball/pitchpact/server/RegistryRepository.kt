package com.infinityball.pitchpact.server

import com.infinityball.pitchpact.domain.Coordination
import com.infinityball.pitchpact.dto.*
import kotlinx.serialization.Serializable
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

// Stored fields per record are exactly what the registry path needs. No roster, guardian,
// availability or player data has any type or column here (see docs/m6-privacy-review.md).
@Serializable private data class TeamRecord(
    val id: String, val listing: RegistryListing, var listed: Boolean,
    var teamCode: String, val ownerKey: String,
)
@Serializable private data class LinkRequestRecord(
    val id: String, val requesterTeamId: String, val recipientTeamId: String,
    var state: String, val expiresAt: Long,
)
@Serializable private data class LinkRecord(val id: String, val teamA: String, val teamB: String, var state: String)
@Serializable private data class FixtureRecord(
    val id: String, val linkId: String, val homeTeamId: String, val awayTeamId: String,
    var startEpochMillis: Long, var location: String, var homeUniform: String, var awayUniform: String,
    var state: String, var confirmedBy: Set<String>, var revision: Int, var expiresAt: Long,
)
@Serializable private data class RegistryState(
    val teams: MutableMap<String, TeamRecord> = mutableMapOf(),
    val codeToTeamId: MutableMap<String, String> = mutableMapOf(),
    val requests: MutableMap<String, LinkRequestRecord> = mutableMapOf(),
    val links: MutableMap<String, LinkRecord> = mutableMapOf(),
    val fixtures: MutableMap<String, FixtureRecord> = mutableMapOf(),
    /** key -> "kind|fingerprint|resultId". Replays with the same fingerprint return the original result. */
    val receipts: MutableMap<String, String> = mutableMapOf(),
)

class RegistryForbidden : RuntimeException()
class RegistryNotFound : RuntimeException()
class RegistryConflict : RuntimeException()

/** Capability-secret registry + link-scoped fixture coordination (docs/protocol.md §5). */
class RegistryRepository(private val directory: Path, private val now: () -> Long = System::currentTimeMillis) {
    private val json = PitchPactJson.codec
    private val random = SecureRandom()
    private val file = directory.resolve("registry-state.json")
    private val state: RegistryState
    private val proposalTtl = 7 * 86_400_000L

    init {
        Files.createDirectories(directory)
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        state = if (Files.exists(file)) json.decodeFromString(RegistryState.serializer(), Files.readString(file)) else RegistryState()
    }

    // ---- plumbing -------------------------------------------------------------------------
    private fun persist() {
        val temp = Files.createTempFile(directory, ".registry-", ".tmp")
        try {
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"))
            Files.writeString(temp, json.encodeToString(RegistryState.serializer(), state))
            FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } finally { Files.deleteIfExists(temp) }
    }
    private fun pick(length: Int, alphabet: String) = (1..length).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    private fun freshCode(): String {
        var next: String
        do { next = "TEAM-" + pick(8, "ABCDEFGHJKLMNPQRSTUVWXYZ23456789") } while (state.codeToTeamId.containsKey(next))
        return next
    }
    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    private fun fingerprint(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun replay(key: String, kind: String, fp: String): String? {
        require(key.length in 16..128)
        val parts = (state.receipts[key] ?: return null).split("|", limit = 3)
        if (parts[0] != kind || parts[1] != fp) throw RegistryConflict()
        return parts[2]
    }
    private fun remember(key: String, kind: String, fp: String, id: String) { state.receipts[key] = "$kind|$fp|$id" }
    private fun owner(teamId: String, key: String): TeamRecord {
        val team = state.teams[teamId] ?: throw RegistryNotFound()
        if (!same(team.ownerKey, key)) throw RegistryForbidden()
        return team
    }
    private fun view(f: FixtureRecord) = CoordinatedFixture(f.id, f.linkId, f.homeTeamId, f.awayTeamId,
        f.startEpochMillis, f.location, f.homeUniform, f.awayUniform, f.state, f.confirmedBy, f.revision)
    private fun requestView(r: LinkRequestRecord): LinkRequestView {
        if (r.state == "pending" && r.expiresAt <= now()) { r.state = "expired"; persist() }
        return LinkRequestView(r.id, r.requesterTeamId, r.recipientTeamId, r.state, r.expiresAt)
    }
    /** Link must exist and be active (revoked => 403 for every write, no matter who asks). */
    private fun activeLink(linkId: String, key: String): Pair<LinkRecord, String> {
        val link = state.links[linkId] ?: throw RegistryNotFound()
        val party = when {
            same(state.teams[link.teamA]?.ownerKey ?: "", key) -> link.teamA
            same(state.teams[link.teamB]?.ownerKey ?: "", key) -> link.teamB
            else -> throw RegistryForbidden()
        }
        if (link.state != "active") throw RegistryForbidden()
        return link to party
    }
    private fun live(f: FixtureRecord): FixtureRecord {
        if (f.state == "proposed" && f.expiresAt <= now()) { f.state = "expired"; persist() }
        return f
    }

    // ---- registry -------------------------------------------------------------------------
    @Synchronized fun create(input: RegistryCreate): RegistryCredentials {
        val l = input.listing
        require(l.clubName.isNotBlank() && l.clubName.length <= 100 && l.region.isNotBlank() && l.region.length <= 80 &&
            l.ageGroup.isNotBlank() && l.ageGroup.length <= 40)
        val fp = fingerprint(json.encodeToString(RegistryListing.serializer(), l))
        replay(input.idempotencyKey, "create", fp)?.let { id ->
            val t = state.teams[id] ?: throw RegistryNotFound()
            return RegistryCredentials(RegistryTeam(t.id, t.listing, t.listed), t.ownerKey, t.teamCode)
        }
        val id = UUID.randomUUID().toString()
        val team = TeamRecord(id, l, true, freshCode(), "OWN-" + pick(40, "abcdefghijklmnopqrstuvwxyz0123456789"))
        state.teams[id] = team; state.codeToTeamId[team.teamCode] = id
        remember(input.idempotencyKey, "create", fp, id); persist()
        return RegistryCredentials(RegistryTeam(id, l, true), team.ownerKey, team.teamCode)
    }
    @Synchronized fun setListed(teamId: String, key: String, listed: Boolean): RegistryTeam {
        val t = owner(teamId, key); t.listed = listed; persist()
        return RegistryTeam(t.id, t.listing, t.listed)
    }
    @Synchronized fun search(query: String?): List<RegistryTeam> = state.teams.values.filter { it.listed }.filter {
        query.isNullOrBlank() || listOf(it.listing.clubName, it.listing.region, it.listing.ageGroup).any { f -> f.contains(query, true) }
    }.map { RegistryTeam(it.id, it.listing, true) }
    /** Old code dies immediately: it is removed from the lookup table in the same persisted step. */
    @Synchronized fun rotateCode(teamId: String, key: String): TeamCode {
        val t = owner(teamId, key)
        state.codeToTeamId.remove(t.teamCode)
        t.teamCode = freshCode(); state.codeToTeamId[t.teamCode] = teamId; persist()
        return TeamCode(t.teamCode)
    }
    @Synchronized fun revokeCode(teamId: String, key: String) {
        val t = owner(teamId, key); state.codeToTeamId.remove(t.teamCode); t.teamCode = ""; persist()
    }

    // ---- link handshake -------------------------------------------------------------------
    /** The team code grants exactly this capability; the requester additionally proves ownership of its own team. */
    @Synchronized fun requestLink(teamCode: String, requesterKey: String, input: LinkRequestInput): LinkRequestView {
        owner(input.requesterTeamId, requesterKey)
        val recipient = state.codeToTeamId[teamCode] ?: throw RegistryNotFound()
        if (recipient == input.requesterTeamId) throw RegistryConflict()
        val fp = fingerprint("$teamCode|${input.requesterTeamId}")
        replay(input.idempotencyKey, "link-request", fp)?.let { return requestView(state.requests[it] ?: throw RegistryNotFound()) }
        val dup = state.requests.values.any { it.state == "pending" && it.requesterTeamId == input.requesterTeamId && it.recipientTeamId == recipient && it.expiresAt > now() }
        if (dup) throw RegistryConflict()
        val req = LinkRequestRecord("REQ-" + UUID.randomUUID().toString().take(12), input.requesterTeamId, recipient, "pending", now() + proposalTtl)
        state.requests[req.id] = req; remember(input.idempotencyKey, "link-request", fp, req.id); persist()
        return requestView(req)
    }
    @Synchronized fun inbox(teamId: String, key: String): List<LinkRequestView> {
        owner(teamId, key)
        return state.requests.values.filter { it.recipientTeamId == teamId }.map(::requestView)
    }
    @Synchronized fun acceptLink(requestId: String, key: String): LinkView {
        val req = state.requests[requestId] ?: throw RegistryNotFound()
        owner(req.recipientTeamId, key)
        if (requestView(req).state != "pending") throw RegistryConflict()
        req.state = "accepted"
        val link = LinkRecord("LNK-" + pick(24, "abcdefghijklmnopqrstuvwxyz0123456789"), req.requesterTeamId, req.recipientTeamId, "active")
        state.links[link.id] = link; persist()
        return LinkView(link.id, link.teamA, link.teamB, link.state)
    }
    @Synchronized fun links(teamId: String, key: String): List<LinkView> {
        owner(teamId, key)
        return state.links.values.filter { it.teamA == teamId || it.teamB == teamId }.map { LinkView(it.id, it.teamA, it.teamB, it.state) }
    }
    @Synchronized fun revokeLink(linkId: String, key: String) {
        val link = state.links[linkId] ?: throw RegistryNotFound()
        if (!same(state.teams[link.teamA]?.ownerKey ?: "", key) && !same(state.teams[link.teamB]?.ownerKey ?: "", key)) throw RegistryForbidden()
        link.state = "revoked"
        // Unconfirmed proposals die with the link; coordinated fixtures stop appearing in schedule().
        state.fixtures.values.filter { it.linkId == linkId && it.state == "proposed" }.forEach { it.state = "revoked" }
        persist()
    }

    // ---- fixtures: confirm-before-show ----------------------------------------------------
    @Synchronized fun propose(linkId: String, key: String, p: FixtureProposal): CoordinatedFixture {
        val (link, party) = activeLink(linkId, key)
        Coordination.validate(p, now())
        val fp = fingerprint("$linkId|$party|${json.encodeToString(FixtureProposal.serializer(), p.copy(idempotencyKey = ""))}")
        replay(p.idempotencyKey, "propose", fp)?.let { return view(state.fixtures[it] ?: throw RegistryNotFound()) }
        val other = if (party == link.teamA) link.teamB else link.teamA
        val f = FixtureRecord("FIX-" + pick(16, "abcdefghijklmnopqrstuvwxyz0123456789"), linkId, party, other,
            p.startEpochMillis, p.location, p.homeUniform, p.awayUniform, "proposed", setOf(party), 1, now() + proposalTtl)
        state.fixtures[f.id] = f; remember(p.idempotencyKey, "propose", fp, f.id); persist()
        return view(f)
    }
    @Synchronized fun revise(linkId: String, fixtureId: String, key: String, p: FixtureProposal): CoordinatedFixture {
        val (_, party) = activeLink(linkId, key)
        val f = state.fixtures[fixtureId]?.takeIf { it.linkId == linkId } ?: throw RegistryNotFound()
        if (f.state == "revoked") throw RegistryConflict()
        Coordination.validate(p, now())
        val fp = fingerprint("$linkId|$fixtureId|$party|${json.encodeToString(FixtureProposal.serializer(), p.copy(idempotencyKey = ""))}")
        replay(p.idempotencyKey, "revise", fp)?.let { return view(f) }
        // Material edits void BOTH confirmations: only the reviser's side is re-confirmed, the other must re-confirm.
        f.startEpochMillis = p.startEpochMillis; f.location = p.location; f.homeUniform = p.homeUniform; f.awayUniform = p.awayUniform
        f.confirmedBy = setOf(party); f.state = "proposed"; f.revision += 1; f.expiresAt = now() + proposalTtl
        remember(p.idempotencyKey, "revise", fp, f.id); persist()
        return view(f)
    }
    @Synchronized fun confirm(linkId: String, fixtureId: String, key: String): CoordinatedFixture {
        val (link, party) = activeLink(linkId, key)
        val f = live(state.fixtures[fixtureId]?.takeIf { it.linkId == linkId } ?: throw RegistryNotFound())
        if (f.state == "coordinated" && party in f.confirmedBy) return view(f) // idempotent
        if (f.state != "proposed") throw RegistryConflict()
        f.confirmedBy = f.confirmedBy + party
        if (f.confirmedBy.containsAll(listOf(link.teamA, link.teamB))) f.state = "coordinated"
        persist()
        return view(f)
    }
    @Synchronized fun linkFixtures(linkId: String, key: String): List<CoordinatedFixture> {
        val link = state.links[linkId] ?: throw RegistryNotFound()
        if (!same(state.teams[link.teamA]?.ownerKey ?: "", key) && !same(state.teams[link.teamB]?.ownerKey ?: "", key)) throw RegistryForbidden()
        return state.fixtures.values.filter { it.linkId == linkId }.map { view(live(it)) }
    }
    /** What a team's schedule may show: coordinated fixtures on still-active links only. */
    @Synchronized fun schedule(teamId: String, key: String): List<CoordinatedFixture> {
        owner(teamId, key)
        return state.fixtures.values.filter {
            it.state == "coordinated" && (it.homeTeamId == teamId || it.awayTeamId == teamId) && state.links[it.linkId]?.state == "active"
        }.map(::view)
    }

    /** Deleting a team deletes every server-side record that references it (protocol §6). */
    @Synchronized fun deleteTeam(teamId: String, key: String) {
        val t = owner(teamId, key)
        val linkIds = state.links.values.filter { it.teamA == teamId || it.teamB == teamId }.map { it.id }.toSet()
        state.fixtures.values.removeAll { it.linkId in linkIds }
        state.links.keys.removeAll(linkIds)
        state.requests.values.removeAll { it.requesterTeamId == teamId || it.recipientTeamId == teamId }
        state.codeToTeamId.remove(t.teamCode)
        state.teams.remove(teamId)
        state.receipts.clear() // receipts may reference deleted ids; clients re-key on retry
        persist()
    }
}
