package com.infinityball.pitchpact.server

import com.infinityball.pitchpact.ConsensusFacade
import com.infinityball.pitchpact.domain.*
import com.infinityball.pitchpact.dto.*
import java.nio.file.Files
import kotlin.test.*
import org.junit.Test
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication

private suspend fun HttpClient.send(path: String, payload: String, key: String? = null): io.ktor.client.statement.HttpResponse = post(path) {
    contentType(ContentType.Application.Json)
    if (key != null) header(HttpHeaders.Authorization, "Bearer $key")
    setBody(payload)
}

/**
 * Issue #7 acceptance: two emulated scorers drive a full contested half over the
 * real HTTP endpoints. After every single action the official scoreboard must
 * equal an independent accepted-only recompute of the record ledger.
 */
class ConsensusIntegrationTest {
    private val codec = PitchPactJson.codec
    private val facade = ConsensusFacade()
    private var clock = 1_000_000L

    private suspend fun HttpClient.register(name: String, key: String): RegistryCredentials =
        codec.decodeFromString(RegistryCredentials.serializer(),
            send("/v0/registry/teams", codec.encodeToString(RegistryCreate.serializer(),
                RegistryCreate(RegistryListing(name, "North", "U10"), "create-$key-idempotency-0001"))).bodyAsText())

    private suspend fun HttpClient.link(home: RegistryCredentials, away: RegistryCredentials, key: String): LinkView {
        val request = codec.decodeFromString(LinkRequestView.serializer(),
            send("/v0/registry/teams/${away.teamCode}/link-requests",
                codec.encodeToString(LinkRequestInput.serializer(), LinkRequestInput(home.team.id, key)), home.ownerKey).bodyAsText())
        return codec.decodeFromString(LinkView.serializer(),
            send("/v0/registry/links/${request.id}/accept", "{}", away.ownerKey).bodyAsText())
    }

    private suspend fun HttpClient.snapshot(link: LinkView, key: String, match: String): ConsensusSnapshot =
        codec.decodeFromString(ConsensusSnapshot.serializer(),
            get("/v0/links/${link.id}/consensus?matchId=$match") { header(HttpHeaders.Authorization, "Bearer $key") }.bodyAsText())

    /** Independent oracle: accepted rows only, goals counted per side. */
    private fun acceptedScore(snapshot: ConsensusSnapshot): Pair<Int, Int> {
        fun count(side: Side) = snapshot.decisions.count {
            it.state == ConsensusState.ACCEPTED && it.change.action == ScoreChangeAction.ADD &&
                ((it.event as? LedgerEvent.Event)?.payload as? EventPayload.Goal).let { g -> g != null && g.goalType != GoalType.OWN_GOAL && g.side == side }
        }
        return count(Side.HOME) to count(Side.AWAY)
    }

    private fun assertOfficialMatchesAcceptedLedger(snapshot: ConsensusSnapshot, context: String) {
        val (home, away) = acceptedScore(snapshot)
        assertEquals(home, snapshot.officialScore.homeScore, "$context home")
        assertEquals(away, snapshot.officialScore.awayScore, "$context away")
    }

    @Test fun twoEmulatedScorersContestAFullHalfAndOfficialScoreTracksAcceptedLedger() = testApplication {
        application { appModule(registryRepository = RegistryRepository(Files.createTempDirectory("contested-half"))) }
        val home = client.register("Home FC", "home-key-0001")
        val away = client.register("Away FC", "away-key-0002")
        val link = client.link(home, away, "contested-link-idempotency-01")
        val match = "fixture-contested"

        // An idempotency receipt is scoped to the signing sideline. Neither team can replay
        // the other's submit or accept receipt using the same key and payload.
        val guardedBody = facade.submitGoal(match, "HOME", "guarded-change", "guarded-event", clock, 9, "guarded-submit-key-0001")
        val guardedPath = "/v0/links/${link.id}/consensus/changes"
        assertEquals(HttpStatusCode.OK, client.send(guardedPath, guardedBody, home.ownerKey).status)
        assertEquals(HttpStatusCode.Conflict, client.send(guardedPath, guardedBody, away.ownerKey).status)
        val guardedReview = facade.reviewBody("guarded-review-key-0001")
        assertEquals(HttpStatusCode.OK, client.send("/v0/links/${link.id}/consensus/changes/guarded-change/reject", guardedReview, away.ownerKey).status)
        assertEquals(HttpStatusCode.Conflict, client.send("/v0/links/${link.id}/consensus/changes/guarded-change/reject", guardedReview, home.ownerKey).status)
        assertEquals(ConsensusState.REJECTED, client.snapshot(link, home.ownerKey, match).decisions.single().state)

        // One scripted action per line: side, jersey, then response ("accept"/"reject"/"withdraw").
        val initialRecords = 1
        val script = listOf(
            Triple(Side.HOME, 9, "accept"), Triple(Side.AWAY, 11, "reject"),
            Triple(Side.AWAY, 11, "withdraw"), Triple(Side.AWAY, 11, "reject"),
            Triple(Side.AWAY, 7, "accept"), Triple(Side.HOME, 9, "withdraw"),
            Triple(Side.HOME, 10, "accept"),
        )
        var sequence = 0
        for ((side, jersey, response) in script) {
            sequence += 1
            clock += 1_000
            val body = facade.submitGoal(match, side.name, "change-$sequence", "event-$sequence", clock, jersey, "submit-$sequence-idempotency-001")
            assertEquals(HttpStatusCode.OK, client.send("/v0/links/${link.id}/consensus/changes", body, side.key(home, away)).status)
            var snap = client.snapshot(link, home.ownerKey, match)
            assertOfficialMatchesAcceptedLedger(snap, "after submit $sequence")
            assertEquals(listOf("change-$sequence"), snap.pendingChanges.map { it.changeId }, "submit $sequence must surface pending")
            when (response) {
                "accept" -> assertEquals(HttpStatusCode.OK, client.send("/v0/consensus/changes/change-$sequence/accept", facade.reviewBody("review-$sequence-idempotency-01"), side.otherKey(home, away)).status)
                "reject" -> assertEquals(HttpStatusCode.OK, client.send("/v0/consensus/changes/change-$sequence/reject", facade.reviewBody("review-$sequence-idempotency-01"), side.otherKey(home, away)).status)
                else -> assertEquals(HttpStatusCode.OK, client.send("/v0/consensus/changes/change-$sequence/withdraw", facade.reviewBody("review-$sequence-idempotency-01"), side.key(home, away)).status)
            }
            snap = client.snapshot(link, home.ownerKey, match)
            assertOfficialMatchesAcceptedLedger(snap, "after $response $sequence")
            assertTrue(snap.pendingChanges.isEmpty(), "no pending may survive action $sequence")
            assertEquals(initialRecords + sequence, snap.decisions.size, "every outcome stays in the record ledger")
        }
        // 3 accepted goals (two home, one away): the scoreboard must read the ledger, never raw submissions.
        assertEquals(2 to 1, acceptedScore(client.snapshot(link, home.ownerKey, match)))

        // One whistle leaves the match live; the opposing whistle makes the final score official.
        client.send("/v0/links/${link.id}/consensus/whistle", facade.whistleBody(match, Side.HOME.name, "whistle-home-00001"), home.ownerKey)
        var final = client.snapshot(link, home.ownerKey, match)
        assertFalse(final.official)
        assertOfficialMatchesAcceptedLedger(final, "after home whistle")
        client.send("/v0/links/${link.id}/consensus/whistle", facade.whistleBody(match, Side.AWAY.name, "whistle-away-00001"), away.ownerKey)
        final = client.snapshot(link, home.ownerKey, match)
        assertTrue(final.official)
        assertOfficialMatchesAcceptedLedger(final, "after both whistles")
        assertEquals(listOf("REJECTED", "ACCEPTED", "REJECTED", "WITHDRAWN", "REJECTED", "ACCEPTED", "WITHDRAWN", "ACCEPTED"),
            final.decisions.map { it.state.name })
    }

    private fun Side.key(home: RegistryCredentials, away: RegistryCredentials) = if (this == Side.HOME) home.ownerKey else away.ownerKey
    private fun Side.otherKey(home: RegistryCredentials, away: RegistryCredentials) = if (this == Side.HOME) away.ownerKey else home.ownerKey
}
