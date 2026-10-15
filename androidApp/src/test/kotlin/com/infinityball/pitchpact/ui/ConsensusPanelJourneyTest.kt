package com.infinityball.pitchpact.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.material3.MaterialTheme
import com.infinityball.pitchpact.dto.*
import com.infinityball.pitchpact.server.RegistryRepository
import com.infinityball.pitchpact.server.appModule
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import java.net.ServerSocket
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #7 Android scorer journey: a real loopback server drives both sideline
 * panels end to end (submit -> opponent accept -> official). Only reachable on
 * CI: aapt2 ships x86_64-only, so the resource pipeline cannot run on this
 * arm64 host. Linux CI (amd64) is the owner of this evidence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConsensusPanelJourneyTest {
    @get:Rule val compose = createComposeRule()

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun waitForServer(port: Int) {
        repeat(40) {
            try {
                (URL("http://127.0.0.1:$port/healthz").openConnection() as HttpURLConnection).let { c ->
                    val code = c.responseCode; c.disconnect()
                    if (code in 200..299) return
                }
            } catch (_: Exception) {}
            Thread.sleep(50)
        }
        error("loopback server never became healthy")
    }

    private fun register(port: Int, name: String, key: String): RegistryCredentials {
        val conn = URL("http://127.0.0.1:$port/v0/registry/teams").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val create = PitchPactJson.codec.encodeToString(RegistryCreate.serializer(),
            RegistryCreate(RegistryListing(name, "Springfield", "U10"), "create-$key-idempotency-01"))
        conn.outputStream.use { it.write(create.toByteArray()) }
        assertEquals(200, conn.responseCode)
        return PitchPactJson.codec.decodeFromString(RegistryCredentials.serializer(), conn.inputStream.bufferedReader().readText())
    }

    private fun requestLink(port: Int, from: RegistryCredentials, to: RegistryCredentials) {
        val conn = URL("http://127.0.0.1:$port/v0/registry/teams/${to.teamCode}/link-requests").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer ${from.ownerKey}")
        conn.outputStream.use { it.write(PitchPactJson.codec.encodeToString(LinkRequestInput.serializer(),
            LinkRequestInput(from.team.id, "link-request-key-000001")).toByteArray()) }
        assertEquals(200, conn.responseCode)
        val request = PitchPactJson.codec.decodeFromString(LinkRequestView.serializer(), conn.inputStream.bufferedReader().readText())
        val accept = URL("http://127.0.0.1:$port/v0/registry/links/${request.id}/accept").openConnection() as HttpURLConnection
        accept.requestMethod = "POST"; accept.doOutput = true
        accept.setRequestProperty("Content-Type", "application/json")
        accept.setRequestProperty("Authorization", "Bearer ${to.ownerKey}")
        accept.outputStream.use { it.write("{}".toByteArray()) }
        assertEquals(200, accept.responseCode)
    }

    private fun linkId(port: Int, team: RegistryCredentials): String {
        val conn = URL("http://127.0.0.1:$port/v0/registry/teams/${team.team.id}/links").openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer ${team.ownerKey}")
        assertEquals(200, conn.responseCode)
        val links = PitchPactJson.codec.decodeFromString(kotlinx.serialization.builtins.ListSerializer(LinkView.serializer()),
            conn.inputStream.bufferedReader().readText())
        return links.single().id
    }

    private fun click(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).performClick()
    }

    private fun type(tag: String, value: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).performTextInput(value)
    }

    private fun expectText(text: String) =
        compose.waitUntil(10_000) { compose.onNodeWithText(text, substring = true).exists() }

    @Test fun scorerSubmitsGoalAndOpposingSidelineAcceptsItIntoTheOfficialScore() {
        val port = freePort()
        val server = embeddedServer(Netty, port = port) {
            appModule(registryRepository = RegistryRepository(java.nio.file.Files.createTempDirectory("panel-journey")))
        }.start(wait = false)
        try {
            waitForServer(port)
            val home = register(port, "Home Kickers", "home-key")
            val away = register(port, "Away Rovers", "away-key")
            requestLink(port, home, away)
            val first = LinkView(linkId(port, home), home.team.id, away.team.id, "active")
            val homeClient = RegistryClient("http://127.0.0.1:$port")
            val awayClient = RegistryClient("http://127.0.0.1:$port")

            compose.setContent {
                MaterialTheme { ConsensusPanel(homeClient, first, home.team.id, home.ownerKey) }
            }
            type("consensus.matchId", "fixture-1")
            type("consensus.jersey", "9")
            click("consensus.submit")
            expectText("OFFICIAL 0-0 · live")
            expectText("Awaiting opponent: add goal #9 (HOME)")
            compose.onNodeWithTag("consensus.withdraw.0").assertExists()

            compose.runOnUiThread { }
            compose.setContent {
                MaterialTheme { ConsensusPanel(awayClient, first, away.team.id, away.ownerKey) }
            }
            type("consensus.matchId", "fixture-1")
            expectText("Review: add goal #9 (HOME)")
            
            click("consensus.accept.0")
            expectText("OFFICIAL 1-0 · live")
            // The record ledger is visible from the accepting side too, with its audit trail.
            expectText("ACCEPTED · add goal #9 (HOME) · by HOME · resolved by AWAY")
        } finally {
            server.stop(100, 200)
        }
    }
}
