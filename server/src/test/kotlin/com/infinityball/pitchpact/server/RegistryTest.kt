package com.infinityball.pitchpact.server

import com.infinityball.pitchpact.dto.*
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
}
