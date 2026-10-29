package com.infinityball.pitchpact.server

import com.infinityball.pitchpact.dto.*
import com.infinityball.pitchpact.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.*
import org.junit.Test

class RegistryTest {
    private val codec = PitchPactJson.codec
    private val future = System.currentTimeMillis() + 30L * 86_400_000L
    private fun proposal(key: String, start: Long = future) = FixtureProposal(start, "Riverside Field 3", "Red shirts", "Blue shirts", key)
    private suspend fun HttpClient.send(path: String, payload: String, key: String? = null): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        if (key != null) header(HttpHeaders.Authorization, "Bearer $key")
        setBody(payload)
    }
    private suspend fun HttpClient.read(path: String, key: String): HttpResponse = get(path) { header(HttpHeaders.Authorization, "Bearer $key") }
    private suspend fun HttpClient.change(path: String, payload: String, key: String): HttpResponse = put(path) {
        contentType(ContentType.Application.Json); header(HttpHeaders.Authorization, "Bearer $key"); setBody(payload)
    }
    private suspend fun HttpClient.register(name: String, id: String): RegistryCredentials {
        val input = RegistryCreate(RegistryListing(name, "Portland", "U10"), "create-$id-idempotency-key-0001")
        return codec.decodeFromString(RegistryCredentials.serializer(), send("/v0/registry/teams", codec.encodeToString(RegistryCreate.serializer(), input)).bodyAsText())
    }
    private suspend fun HttpClient.link(from: RegistryCredentials, to: RegistryCredentials): LinkView {
        val request = codec.decodeFromString(LinkRequestView.serializer(), send("/v0/registry/teams/${to.teamCode}/link-requests",
            codec.encodeToString(LinkRequestInput.serializer(), LinkRequestInput(from.team.id, "request-idempotency-key-0001")), from.ownerKey).bodyAsText())
        assertEquals(HttpStatusCode.Forbidden, send("/v0/registry/links/${request.id}/accept", "{}", from.ownerKey).status)
        return codec.decodeFromString(LinkView.serializer(), send("/v0/registry/links/${request.id}/accept", "{}", to.ownerKey).bodyAsText())
    }
    private suspend fun HttpClient.schedule(team: RegistryCredentials) = codec.decodeFromString(
        kotlinx.serialization.builtins.ListSerializer(CoordinatedFixture.serializer()),
        read("/v0/registry/teams/${team.team.id}/schedule", team.ownerKey).bodyAsText())

    @Test fun twoSideHandshakeAndRevocation() = testApplication {
        val dir = Files.createTempDirectory("registry-test")
        application { appModule(registryRepository = RegistryRepository(dir)) }
        val a = client.register("Falcons SC", "falcons")
        val b = client.register("Riverside Rush", "riverside")
        val listing = client.get("/v0/registry/teams?q=Falcons").bodyAsText()
        assertTrue(listing.contains("Falcons SC"))
        assertFalse(listing.contains(a.teamCode) || listing.contains(a.ownerKey))
        val visibility = codec.encodeToString(RegistryVisibility.serializer(), RegistryVisibility(false))
        assertEquals(HttpStatusCode.OK, client.change("/v0/registry/teams/${a.team.id}/listing", visibility, a.ownerKey).status)
        assertFalse(client.get("/v0/registry/teams?q=Falcons").bodyAsText().contains("Falcons SC"))
        client.change("/v0/registry/teams/${a.team.id}/listing", codec.encodeToString(RegistryVisibility.serializer(), RegistryVisibility(true)), a.ownerKey)
        val requestBody = codec.encodeToString(LinkRequestInput.serializer(), LinkRequestInput(a.team.id, "request-idempotency-key-0001"))
        assertEquals(HttpStatusCode.Unauthorized, client.send("/v0/registry/teams/${b.teamCode}/link-requests", requestBody).status)
        assertEquals(HttpStatusCode.Forbidden, client.send("/v0/registry/teams/${b.teamCode}/link-requests", requestBody, b.ownerKey).status)
        val link = client.link(a, b)
        val one = proposal("fixture-propose-key-0001")
        val proposeResp = client.send("/v0/links/${link.id}/fixtures",
            codec.encodeToString(FixtureProposal.serializer(), one), a.ownerKey)
        assertEquals(HttpStatusCode.OK, proposeResp.status, "propose body=${proposeResp.bodyAsText()}")
        val fixture = codec.decodeFromString(CoordinatedFixture.serializer(), proposeResp.bodyAsText())
        assertEquals("proposed", fixture.state)
        assertTrue(client.schedule(a).isEmpty() && client.schedule(b).isEmpty())
        assertEquals(HttpStatusCode.Forbidden, client.send("/v0/links/${link.id}/fixtures/${fixture.id}/confirm", "{}", "invalid-owner-key").status)
        assertEquals(HttpStatusCode.BadRequest, client.send("/v0/links/${link.id}/fixtures",
            codec.encodeToString(FixtureProposal.serializer(), one.copy(idempotencyKey = "invalid-fixture-key-0002", location = " ")), a.ownerKey).status)
        assertEquals("coordinated", codec.decodeFromString(CoordinatedFixture.serializer(), client.send(
            "/v0/links/${link.id}/fixtures/${fixture.id}/confirm", "{}", b.ownerKey).bodyAsText()).state)
        assertEquals(listOf(fixture.id), client.schedule(a).map { it.id })
        assertEquals(listOf(fixture.id), client.schedule(b).map { it.id })
        val revised = codec.decodeFromString(CoordinatedFixture.serializer(), client.change(
            "/v0/links/${link.id}/fixtures/${fixture.id}",
            codec.encodeToString(FixtureProposal.serializer(), proposal("fixture-revise-key-0002", future + 86_400_000L)), a.ownerKey).bodyAsText())
        assertEquals("proposed", revised.state)
        assertEquals(2, revised.revision)
        assertTrue(client.schedule(a).isEmpty() && client.schedule(b).isEmpty())
        val revoked = client.delete("/v0/links/${link.id}") { header(HttpHeaders.Authorization, "Bearer ${b.ownerKey}") }
        assertEquals(HttpStatusCode.OK, revoked.status)
        assertEquals(HttpStatusCode.Forbidden, client.send("/v0/links/${link.id}/fixtures",
            codec.encodeToString(FixtureProposal.serializer(), proposal("post-revoke-key-0003")), a.ownerKey).status)
        assertEquals(HttpStatusCode.Forbidden, client.send("/v0/links/${link.id}/fixtures/${fixture.id}/confirm", "{}", a.ownerKey).status)
        assertFalse(Files.readString(dir.resolve("registry-state.json")).contains("guardian"))
    }

    @Test fun oldCodesDieOnRotateAndRevoke() = testApplication {
        val dir = Files.createTempDirectory("registry-rotate")
        application { appModule(registryRepository = RegistryRepository(dir)) }
        val a = client.register("Rotators", "rotators")
        val b = client.register("Seekers", "seekers")
        assertEquals(HttpStatusCode.Forbidden, client.send("/v0/registry/teams/${a.team.id}/code/rotate", "{}", b.ownerKey).status)
        val rotated = codec.decodeFromString(TeamCode.serializer(), client.send("/v0/registry/teams/${a.team.id}/code/rotate", "{}", a.ownerKey).bodyAsText())
        assertNotEquals(a.teamCode, rotated.teamCode)
        val body = codec.encodeToString(LinkRequestInput.serializer(), LinkRequestInput(b.team.id, "rotation-link-key-00001"))
        assertEquals(HttpStatusCode.NotFound, client.send("/v0/registry/teams/${a.teamCode}/link-requests", body, b.ownerKey).status)
        assertEquals(HttpStatusCode.OK, client.send("/v0/registry/teams/${rotated.teamCode}/link-requests", body, b.ownerKey).status)
        client.delete("/v0/registry/teams/${a.team.id}/code") { header(HttpHeaders.Authorization, "Bearer ${a.ownerKey}") }
        assertEquals(HttpStatusCode.NotFound, client.send("/v0/registry/teams/${rotated.teamCode}/link-requests",
            codec.encodeToString(LinkRequestInput.serializer(), LinkRequestInput(b.team.id, "revoked-link-key-00002")), b.ownerKey).status)
    }

    @Test fun expiryPersistenceReplayAndDeletion() = testApplication {
        val dir = Files.createTempDirectory("registry-expire")
        var now = 1_000_000L
        val repo = RegistryRepository(dir) { now }
        application { appModule(registryRepository = repo) }
        val a = client.register("Patient FC", "patient")
        val b = client.register("Later SC", "later")
        val input = LinkRequestInput(a.team.id, "expiry-request-key-00001")
        val body = codec.encodeToString(LinkRequestInput.serializer(), input)
        val req = codec.decodeFromString(LinkRequestView.serializer(), client.send("/v0/registry/teams/${b.teamCode}/link-requests", body, a.ownerKey).bodyAsText())
        assertEquals(req.id, codec.decodeFromString(LinkRequestView.serializer(), client.send("/v0/registry/teams/${b.teamCode}/link-requests", body, a.ownerKey).bodyAsText()).id)
        assertEquals(HttpStatusCode.Conflict, client.send("/v0/registry/teams/${b.teamCode}/link-requests",
            codec.encodeToString(LinkRequestInput.serializer(), input.copy(requesterTeamId = b.team.id)), b.ownerKey).status)
        now += 8 * 86_400_000L
        assertEquals(HttpStatusCode.Conflict, client.send("/v0/registry/links/${req.id}/accept", "{}", b.ownerKey).status)
        assertTrue(client.read("/v0/registry/teams/${b.team.id}/link-requests", b.ownerKey).bodyAsText().contains("expired"))
        assertEquals("expired", RegistryRepository(dir) { now }.inbox(b.team.id, b.ownerKey).first().state)
        val deleted = client.delete("/v0/registry/teams/${a.team.id}") { header(HttpHeaders.Authorization, "Bearer ${a.ownerKey}") }
        assertEquals(HttpStatusCode.OK, deleted.status)
        assertFalse(Files.readString(dir.resolve("registry-state.json")).contains("Patient FC"))
        assertFalse(Files.readString(dir.resolve("registry-state.json")).contains(a.ownerKey))
    }

    @Test fun consensusTwoScorerHandshakeAuthorizationAndLedger() = testApplication {
        val dir = Files.createTempDirectory("registry-consensus")
        application { appModule(registryRepository = RegistryRepository(dir)) }
        val home = client.register("Home Reds", "home-consensus")
        val away = client.register("Away Blues", "away-consensus")
        val link = client.link(home, away)
        val match = "fixture-consensus-1"
        val goal = LedgerEvent.Event("goal-home-1", Side.HOME, 1_000, EventPayload.Goal(Side.HOME, 9))
        val change = ScoreChange("change-home-1", match, Side.HOME, goal.eventId, ScoreChangeAction.ADD)
        fun submitPayload(c: ScoreChange, g: LedgerEvent, key: String) =
            codec.encodeToString(ConsensusSubmit.serializer(), ConsensusSubmit(c, g, "consensus-submit-$key"))
        suspend fun review(key: String, id: String, action: ConsensusAction, actionKey: String) =
            client.send("/v0/links/${link.id}/consensus/changes/${id}/${action.name.lowercase()}", codec.encodeToString(ConsensusReview.serializer(), ConsensusReview(actionKey)), key)
        val submitted = codec.decodeFromString(ConsensusDecision.serializer(), client.send(
            "/v0/links/${link.id}/consensus/changes", submitPayload(change, goal, "submit-home-1"), home.ownerKey).bodyAsText())
        assertEquals(ConsensusState.PENDING, submitted.state)
        assertEquals(submitted, codec.decodeFromString(ConsensusDecision.serializer(), client.send(
            "/v0/links/${link.id}/consensus/changes", submitPayload(change, goal, "submit-home-1"), home.ownerKey).bodyAsText()))
        assertEquals(HttpStatusCode.Conflict, client.send("/v0/links/${link.id}/consensus/changes",
            codec.encodeToString(ConsensusSubmit.serializer(), ConsensusSubmit(change.copy(ledgerEventId = "other"), goal, "submit-home-different")), home.ownerKey).status)
        assertEquals(HttpStatusCode.Forbidden, review(home.ownerKey, change.changeId, ConsensusAction.ACCEPT, "self-accept-00000001").status)
        // Opposing team can submit its own simultaneous pending change.
        val awayGoal = LedgerEvent.Event("goal-away-1", Side.AWAY, 1_100, EventPayload.Goal(Side.AWAY, 11))
        val awayChange = ScoreChange("change-away-1", match, Side.AWAY, awayGoal.eventId, ScoreChangeAction.ADD)
        assertEquals(ConsensusState.PENDING, codec.decodeFromString(ConsensusDecision.serializer(), client.send(
            "/v0/links/${link.id}/consensus/changes", submitPayload(awayChange, awayGoal, "submit-away-1"), away.ownerKey).bodyAsText()).state)
        val snapshot = codec.decodeFromString(ConsensusSnapshot.serializer(), client.read("/v0/links/${link.id}/consensus?matchId=$match", home.ownerKey).bodyAsText())
        assertEquals("0-0", "${snapshot.officialScore.homeScore}-${snapshot.officialScore.awayScore}")
        assertEquals(2, snapshot.pendingChanges.size)
        assertFalse(snapshot.official)
        // Accepting one side's change only makes that side official.
        val accepted = codec.decodeFromString(ConsensusDecision.serializer(), review(away.ownerKey, change.changeId, ConsensusAction.ACCEPT, "accept-home-0000001").bodyAsText())
        assertEquals(ConsensusState.ACCEPTED, accepted.state)
        val official = codec.decodeFromString(ConsensusSnapshot.serializer(), client.read("/v0/links/${link.id}/consensus?matchId=$match", away.ownerKey).bodyAsText())
        assertEquals("1-0", "${official.officialScore.homeScore}-${official.officialScore.awayScore}")
        assertEquals(1, official.pendingChanges.size)
        // Reject opposing goal; terminal records remain forever.
        val awayRejected = codec.decodeFromString(ConsensusDecision.serializer(), review(home.ownerKey, awayChange.changeId, ConsensusAction.REJECT, "reject-away-0000001").bodyAsText())
        assertEquals(ConsensusState.REJECTED, awayRejected.state)
        // The opposing side cannot withdraw another team's change.
        assertEquals(HttpStatusCode.Conflict, review(away.ownerKey, change.changeId, ConsensusAction.WITHDRAW, "withdraw-wrong-000001").status)
        val ledger = client.read("/v0/links/${link.id}/consensus/ledger?matchId=$match", home.ownerKey).bodyAsText()
        assertTrue(ledger.contains("change-home-1") && ledger.contains("ACCEPTED") && ledger.contains("change-away-1") && ledger.contains("REJECTED"))
        // Whistle before opposing change is resolved cannot mark match official.
        val whistle = codec.encodeToString(ConsensusWhistle.serializer(), ConsensusWhistle(match, Side.HOME, "whistle-home-00000001"))
        assertFalse(codec.decodeFromString(ConsensusSnapshot.serializer(), client.send("/v0/links/${link.id}/consensus/whistle", whistle, home.ownerKey).bodyAsText()).official)
        assertEquals(HttpStatusCode.BadRequest, client.send("/v0/links/${link.id}/consensus/whistle",
            codec.encodeToString(ConsensusWhistle.serializer(), ConsensusWhistle(match, Side.AWAY, "wrong-side-000000001")), home.ownerKey).status)
        val firstFinal = codec.decodeFromString(ConsensusSnapshot.serializer(), client.send("/v0/links/${link.id}/consensus/whistle", whistle, home.ownerKey).bodyAsText())
        assertEquals(0, firstFinal.pendingChanges.size)
        assertFalse(firstFinal.official, "one final whistle is never enough")
        val finalAway = codec.decodeFromString(ConsensusSnapshot.serializer(), client.send("/v0/links/${link.id}/consensus/whistle",
            codec.encodeToString(ConsensusWhistle.serializer(), ConsensusWhistle(match, Side.AWAY, "whistle-away-00000001")), away.ownerKey).bodyAsText())
        assertTrue(finalAway.official)
        assertEquals("1-0", "${finalAway.officialScore.homeScore}-${finalAway.officialScore.awayScore}")
        assertEquals(2, finalAway.events.size)
    }

    @Test fun linkedBroadcastRequiresTeamCapabilityAndMirrorsConsensusWithoutLosingOtherEvents() = testApplication {
        val registry = RegistryRepository(Files.createTempDirectory("registry-linked-broadcast"))
        val broadcast = BroadcastRepository(Files.createTempDirectory("broadcast-linked-consensus"))
        application { appModule(broadcastRepository = broadcast, registryRepository = registry) }
        val home = client.register("Broadcast Reds", "home-broadcast")
        val away = client.register("Broadcast Blues", "away-broadcast")
        val link = client.link(home, away)
        val match = "broadcast-match-1"
        // Production clients encode coordinated:fixture IDs through the shared facade.
        val create = com.infinityball.pitchpact.BroadcastFacade().create("coordinated:$match", "Reds", "Blues", "broadcast-create-0001", link.id)
        assertEquals(HttpStatusCode.Forbidden, client.send("/v0/broadcasts", create).status)
        assertEquals(HttpStatusCode.Forbidden, client.send("/v0/broadcasts", create, "spectator-code").status)
        val started = codec.decodeFromString(BroadcastStarted.serializer(), client.send("/v0/broadcasts", create, home.ownerKey).bodyAsText())
        val goal = LedgerEvent.Event("linked-goal-1", Side.HOME, 1_000, EventPayload.Goal(Side.HOME, 9))
        val change = ScoreChange("linked-change-1", match, Side.HOME, goal.eventId, ScoreChangeAction.ADD)
        client.send("/v0/links/${link.id}/consensus/changes", codec.encodeToString(ConsensusSubmit.serializer(), ConsensusSubmit(change, goal, "linked-submit-0001")), home.ownerKey)
        val pending = codec.decodeFromString(SpectatorView.serializer(), client.get("/v0/spectate/${started.spectatorCode}").bodyAsText())
        assertEquals(0, pending.officialScore.homeScore)
        assertEquals(listOf(change.changeId), pending.pendingChanges.map { it.changeId })
        assertEquals(listOf(goal.eventId), pending.events.map { it.eventId })
        assertEquals(HttpStatusCode.NotFound, client.post("/v0/spectate/${started.spectatorCode}").status)
        client.send("/v0/links/${link.id}/consensus/changes/${change.changeId}/accept", "{\"idempotencyKey\":\"linked-accept-0001\"}", away.ownerKey)
        val accepted = codec.decodeFromString(SpectatorView.serializer(), client.get("/v0/spectate/${started.spectatorCode}").bodyAsText())
        assertEquals(1, accepted.officialScore.homeScore)
        assertTrue(accepted.pendingChanges.isEmpty())
        assertEquals(listOf(goal.eventId), accepted.events.map { it.eventId })
    }

    @Test fun consensusStateIsPerMatchAndDeletingTeamErasesIt() = testApplication {
        val dir = Files.createTempDirectory("registry-consensus-delete")
        application { appModule(registryRepository = RegistryRepository(dir)) }
        val home = client.register("Match A Reds", "home-match-a")
        val away = client.register("Match A Blues", "away-match-a")
        val link = client.link(home, away)
        suspend fun submit(match: String, key: String): Pair<ScoreChange, LedgerEvent> {
            val goal = LedgerEvent.Event("goal-$match", Side.HOME, 1_000, EventPayload.Goal(Side.HOME, 9))
            val change = ScoreChange("change-$match", match, Side.HOME, goal.eventId, ScoreChangeAction.ADD)
            assertEquals(HttpStatusCode.OK, client.send("/v0/links/${link.id}/consensus/changes",
                codec.encodeToString(ConsensusSubmit.serializer(), ConsensusSubmit(change, goal, "submit-$match")), home.ownerKey).status)
            return change to goal
        }
        val (first, _) = submit("match-one", "one")
        val (second, _) = submit("match-two", "two")
        assertEquals("0-0", codec.decodeFromString(ConsensusSnapshot.serializer(), client.read("/v0/links/${link.id}/consensus?matchId=match-one", home.ownerKey).bodyAsText()).officialScore.let { "${it.homeScore}-${it.awayScore}" })
        assertEquals(1, codec.decodeFromString(ConsensusSnapshot.serializer(), client.read("/v0/links/${link.id}/consensus?matchId=match-two", away.ownerKey).bodyAsText()).pendingChanges.size)
        // A change from one match cannot be reviewed in another link's match path.
        assertEquals(HttpStatusCode.OK, client.send("/v0/links/${link.id}/consensus/changes/${first.changeId}/accept", "{\"idempotencyKey\":\"accept-first-0000001\"}", away.ownerKey).status)
        assertEquals(HttpStatusCode.OK, client.send("/v0/links/${link.id}/consensus/changes/${second.changeId}/reject", "{\"idempotencyKey\":\"reject-second-000001\"}", away.ownerKey).status)
        assertEquals(HttpStatusCode.OK, client.delete("/v0/registry/teams/${home.team.id}") { header(HttpHeaders.Authorization, "Bearer ${home.ownerKey}") }.status)
        assertEquals(HttpStatusCode.NotFound, client.read("/v0/links/${link.id}/consensus?matchId=match-one", away.ownerKey).status)
    }
}
