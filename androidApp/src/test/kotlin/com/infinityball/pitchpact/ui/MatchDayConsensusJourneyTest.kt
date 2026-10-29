package com.infinityball.pitchpact.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.infinityball.pitchpact.ConsensusFacade
import com.infinityball.pitchpact.MatchDayFacade
import com.infinityball.pitchpact.data.ConsensusOutboxStore
import com.infinityball.pitchpact.domain.MatchDayCodec
import com.infinityball.pitchpact.dto.*
import com.infinityball.pitchpact.server.RegistryRepository
import com.infinityball.pitchpact.server.appModule
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the production match-day submission composable through a real loopback Ktor server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MatchDayConsensusJourneyTest {
    @get:Rule val compose = createComposeRule()

    private fun call(port: Int, path: String, method: String = "GET", key: String? = null, body: String? = null): String {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            if (key != null) conn.setRequestProperty("Authorization", "Bearer $key")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            assertEquals("$method $path", 200, conn.responseCode)
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }

    @Test fun offlineMatchDayCaptureQueuesThenRestartFlushesPendingOnly() {
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, port = port) {
            appModule(registryRepository = RegistryRepository(java.nio.file.Files.createTempDirectory("match-consensus")))
        }.start(wait = false)
        try {
            var ready = false
            for (attempt in 0 until 40) {
                try { if (call(port, "/healthz").isNotEmpty()) { ready = true; break } }
                catch (_: Throwable) { Thread.sleep(50) }
            }
            assertTrue("loopback server never became healthy", ready)
            fun register(name: String, idem: String): RegistryCredentials = PitchPactJson.codec.decodeFromString(
                RegistryCredentials.serializer(), call(port, "/v0/registry/teams", "POST", body =
                    PitchPactJson.codec.encodeToString(RegistryCreate.serializer(),
                        RegistryCreate(RegistryListing(name, "Springfield", "U10"), idem))))
            val home = register("Kickers", "create-home-journey-0001")
            val away = register("Rovers", "create-away-journey-0001")
            val request = PitchPactJson.codec.decodeFromString(LinkRequestView.serializer(),
                call(port, "/v0/registry/teams/${away.teamCode}/link-requests", "POST", home.ownerKey,
                    PitchPactJson.codec.encodeToString(LinkRequestInput.serializer(),
                        LinkRequestInput(home.team.id, "link-journey-0000001"))))
            call(port, "/v0/registry/links/${request.id}/accept", "POST", away.ownerKey, "{}")
            val link = PitchPactJson.codec.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(LinkView.serializer()),
                call(port, "/v0/registry/teams/${home.team.id}/links", key = home.ownerKey)).single()
            val endpoint = "http://127.0.0.1:$port"
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val store = ConsensusOutboxStore(context)
            val facade = MatchDayFacade()
            val fixtureId = "coordinated:fixture-journey-1"
            val eventId = "goal-journey-00000001"
            // Real local match-day capture remains unchanged while the link is offline.
            val captured = facade.capture(facade.initial(fixtureId), eventId, "HOME", 1000,
                "OPEN_PLAY", 9, -1, 10)
            assertEquals(listOf(eventId), MatchDayCodec.decode(captured).queuedIds)
            assertEquals("OFFICIAL — (no linked result synced)", facade.scoreLabels(captured, true, null)[0])
            val generation = mutableStateOf(0)
            compose.setContent {
                key(generation.value) {
                    MaterialTheme {
                        MatchDayConsensusSection(captured, endpoint, link, home, home.team.id, away.team.id, store)
                    }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("match.submitGoal.$eventId").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("match.submitGoal.$eventId").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("1 waiting to send", substring = true).fetchSemanticsNodes().isNotEmpty() }
            val before = store.load(endpoint, link.id, home.team.id)!!
            val rules = ConsensusFacade()
            assertEquals(eventId, rules.outboxId(rules.outboxHead(before)))
            // New composition and a new file-backed store simulate the app after process death.
            compose.runOnUiThread { generation.value++ }
            compose.waitForIdle()
            assertEquals(before, ConsensusOutboxStore(context).load(endpoint, link.id, home.team.id))
            compose.onNodeWithTag("match.flushConsensus").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("All consensus changes delivered", substring = true).fetchSemanticsNodes().isNotEmpty() }
            val after = ConsensusOutboxStore(context).load(endpoint, link.id, home.team.id)!!
            assertTrue(rules.outboxContains(after, eventId))
            assertEquals("", rules.outboxHead(after))
            val snapshot = call(port, "/v0/links/${link.id}/consensus?matchId=fixture-journey-1", key = away.ownerKey)
            assertTrue(rules.scoreboard(snapshot).contains("OFFICIAL 0-0"))
            assertTrue(rules.pendingRows(snapshot, "AWAY").single().contains("Review: add goal #9 (HOME)"))
        } finally { server.stop(100, 200) }
    }
}
