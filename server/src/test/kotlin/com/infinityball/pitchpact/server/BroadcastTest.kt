package com.infinityball.pitchpact.server

import com.infinityball.pitchpact.dto.*
import com.infinityball.pitchpact.domain.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.*
import org.junit.Test

class BroadcastTest {
    private val codec = PitchPactJson.codec
    @Test fun createPushWatchRestartExpireAndRejectSpectatorWrites() = testApplication {
        val dir = Files.createTempDirectory("broadcast-test")
        var now = 1_000_000L
        try {
            var repo = BroadcastRepository(dir) { now }
            val traffic = BroadcastTraffic { now }
            application { appModule(broadcastRepository = repo, traffic = traffic) { id, header -> id == "match-1" && header == "Bearer manager-capability" } }
            val create = BroadcastCreate("match-1", "Home <script>alert(1)</script>", "Away", "number-only", idempotencyKey = "create-idempotency-key")
            suspend fun start(body: BroadcastCreate, auth: Boolean = true) = client.post("/v0/broadcasts") {
                contentType(ContentType.Application.Json)
                if (auth) header(HttpHeaders.Authorization, "Bearer manager-capability")
                setBody(codec.encodeToString(BroadcastCreate.serializer(), body))
            }
            assertEquals(HttpStatusCode.Forbidden, start(create, false).status)
            val first = start(create)
            assertEquals(HttpStatusCode.OK, first.status)
            val started = codec.decodeFromString(BroadcastStarted.serializer(), first.bodyAsText())
            assertTrue(started.spectatorCode.matches(Regex("[A-Z0-9]{6}")))
            assertTrue(started.broadcastSessionKey.startsWith("BSK-") && started.broadcastSessionKey.length >= 48)
            assertEquals(started, codec.decodeFromString(BroadcastStarted.serializer(), start(create).bodyAsText()))
            assertEquals(HttpStatusCode.BadRequest, start(create.copy(homeTeam = "different")).status)
            val goal = LedgerEvent.Event("goal-1", Side.HOME, now, EventPayload.Goal(Side.HOME, 7))
            val pending = StoredScoreDecision(ScoreChange("change-1", "match-1", Side.HOME, goal.eventId, ScoreChangeAction.ADD), ConsensusState.PENDING, Side.HOME)
            val update = BroadcastUpdate(1, "push-idempotency-key", listOf(goal), listOf(pending), MatchClock())
            val body = codec.encodeToString(BroadcastUpdate.serializer(), update).replace("\"jerseyNumber\":7", "\"jerseyNumber\":7,\"playerName\":\"Private Child\"")
            suspend fun patch(content: String, key: String? = started.broadcastSessionKey) = client.patch("/v0/broadcasts/${started.spectatorCode}") {
                contentType(ContentType.Application.Json)
                if (key != null) header(HttpHeaders.Authorization, "Bearer $key")
                setBody(content)
            }
            assertEquals(HttpStatusCode.Unauthorized, patch(body, null).status)
            assertEquals(HttpStatusCode.Forbidden, patch(body, "wrong-key").status)
            assertEquals(HttpStatusCode.OK, patch(body).status)
            assertEquals(HttpStatusCode.OK, patch(body).status)
            assertEquals(HttpStatusCode.Conflict, patch(codec.encodeToString(BroadcastUpdate.serializer(), update.copy(seq = 3))).status)
            val forbiddenDecision = update.copy(seq = 2, idempotencyKey = "next-idempotency-key", decisions = listOf(pending.copy(state = ConsensusState.ACCEPTED, actor = Side.AWAY)))
            assertEquals(HttpStatusCode.Conflict, patch(codec.encodeToString(BroadcastUpdate.serializer(), forbiddenDecision)).status)
            val spectator = client.get("/v0/spectate/${started.spectatorCode}")
            assertEquals(HttpStatusCode.OK, spectator.status)
            val view = codec.decodeFromString(SpectatorView.serializer(), spectator.bodyAsText())
            assertEquals(0, view.officialScore.homeScore)
            assertEquals(1, view.pendingChanges.size)
            assertEquals(listOf(goal), view.events)
            assertFalse(spectator.bodyAsText().contains("Private Child"))
            assertFalse(Files.readString(dir.resolve("${started.spectatorCode}.json")).contains("Private Child"))
            val page = client.get("/spectate/${started.spectatorCode}")
            assertEquals(HttpStatusCode.OK, page.status)
            assertFalse(page.bodyAsText().contains("<script>"))
            assertTrue(page.bodyAsText().contains("&lt;script&gt;"))
            assertEquals(HttpStatusCode.NotFound, client.post("/v0/spectate/${started.spectatorCode}").status)
            assertEquals(HttpStatusCode.NotFound, client.patch("/v0/spectate/${started.spectatorCode}").status)
            repo = BroadcastRepository(dir) { now }
            assertEquals(listOf(goal), repo.spectate(started.spectatorCode).events)
            val stopped = client.delete("/v0/broadcasts/${started.spectatorCode}") { header(HttpHeaders.Authorization, "Bearer ${started.broadcastSessionKey}") }
            assertEquals(HttpStatusCode.NoContent, stopped.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/v0/spectate/${started.spectatorCode}").status)
            val second = repo.create(create.copy(idempotencyKey = "new-idempotency-key"))
            now = second.expiresAt
            assertFailsWith<MissingBroadcast> { repo.spectate(second.spectatorCode) }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun oldRetriesKeepOriginalAckAcrossRestartAndRejectChangedKeyContent() {
        val dir = Files.createTempDirectory("broadcast-replay")
        var now = 1_000_000L
        try {
            var repo = BroadcastRepository(dir) { now }
            val start = repo.create(BroadcastCreate("match", "Home", "Away", idempotencyKey = "create-replay-token"))
            val first = BroadcastUpdate(1, "first-update-token", emptyList(), emptyList(), MatchClock())
            val ack = repo.update(start.spectatorCode, start.broadcastSessionKey, first)
            now += 60_000
            val second = first.copy(seq = 2, idempotencyKey = "second-update-token", clock = MatchClock(MatchPhase.FULL_TIME, 1000))
            repo.update(start.spectatorCode, start.broadcastSessionKey, second)
            repo = BroadcastRepository(dir) { now }
            assertEquals(ack, repo.update(start.spectatorCode, start.broadcastSessionKey, first))
            assertFailsWith<IllegalArgumentException> {
                repo.update(start.spectatorCode, start.broadcastSessionKey, second.copy(seq = 3, idempotencyKey = first.idempotencyKey))
            }
            assertEquals(second.clock, repo.spectate(start.spectatorCode).clock)
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun fullTimeGraceRotationAndRateLimits() {
        val dir = Files.createTempDirectory("broadcast-policy")
        var now = 1_000_000L
        try {
            val repo = BroadcastRepository(dir) { now }
            val started = repo.create(BroadcastCreate("match", "Home", "Away", idempotencyKey = "unique-create-token"))
            val rotated = repo.rotate(started.spectatorCode, started.broadcastSessionKey)
            assertNotEquals(started.broadcastSessionKey, rotated.broadcastSessionKey)
            assertFailsWith<UnauthorizedBroadcast> { repo.update(started.spectatorCode, started.broadcastSessionKey, BroadcastUpdate(1, "push-idempotency-key", emptyList(), emptyList(), MatchClock())) }
            val clock = MatchClock(MatchPhase.FULL_TIME, 5_400_000)
            val ack = repo.update(started.spectatorCode, rotated.broadcastSessionKey, BroadcastUpdate(1, "push-idempotency-key", emptyList(), emptyList(), clock))
            assertEquals(now + 3_600_000, ack.expiresAt)
            now += 3_600_000
            assertFailsWith<MissingBroadcast> { repo.spectate(started.spectatorCode) }
            val traffic = BroadcastTraffic { now }
            repeat(120) { assertTrue(traffic.allow("read:ABC123", "test-ip", 120)) }
            assertFalse(traffic.allow("read:ABC123", "test-ip", 120))
            assertTrue(traffic.allow("read:ABC123", "other-ip", 120))
            assertTrue(traffic.allow("read:XYZ789", "test-ip", 120))
            now += 60_000
            assertTrue(traffic.allow("read:ABC123", "test-ip", 120))
        } finally { dir.toFile().deleteRecursively() }
    }
}
