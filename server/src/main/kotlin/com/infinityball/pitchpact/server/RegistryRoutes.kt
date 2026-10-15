package com.infinityball.pitchpact.server

import com.infinityball.pitchpact.dto.*
import com.infinityball.pitchpact.domain.ConsensusAction
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/** Only owner capabilities may mutate own team, requests or linked fixtures. Never log secrets. */
private suspend inline fun <reified T : Any> ApplicationCall.reply(value: T, serializer: KSerializer<T>) =
    respondText(PitchPactJson.codec.encodeToString(serializer, value), ContentType.Application.Json)

fun Route.registryRoutes(repo: RegistryRepository, traffic: BroadcastTraffic) {
    suspend fun ApplicationCall.write(block: suspend (String) -> Unit) {
        val key = request.header(HttpHeaders.Authorization)?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
            ?: run { respond(HttpStatusCode.Unauthorized); return }
        try { block(key) }
        catch (_: RegistryForbidden) { respond(HttpStatusCode.Forbidden) }
        catch (_: RegistryNotFound) { respond(HttpStatusCode.NotFound) }
        catch (_: RegistryConflict) { respond(HttpStatusCode.Conflict) }
        catch (_: IllegalArgumentException) { respond(HttpStatusCode.BadRequest) }
    }
    post("/v0/registry/teams") {
        if (!traffic.allow("registry-create", call.request.origin.remoteAddress, 20)) { call.respond(HttpStatusCode.TooManyRequests); return@post }
        val input = call.receive<RegistryCreate>()
        try { call.reply(repo.create(input), RegistryCredentials.serializer()) }
        catch (_: RegistryConflict) { call.respond(HttpStatusCode.Conflict) }
        catch (_: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest) }
    }
    get("/v0/registry/teams") {
        if (!traffic.allow("registry-search", call.request.origin.remoteAddress, 120)) { call.respond(HttpStatusCode.TooManyRequests); return@get }
        call.reply(repo.search(call.request.queryParameters["q"]?.take(100)), ListSerializer(RegistryTeam.serializer()))
    }
    put("/v0/registry/teams/{id}/listing") {
        val visibility = call.receive<RegistryVisibility>()
        call.write { key -> call.reply(repo.setListed(call.parameters["id"] ?: "", key, visibility.listed), RegistryTeam.serializer()) }
    }
    post("/v0/registry/teams/{id}/code/rotate") {
        call.write { key -> call.reply(repo.rotateCode(call.parameters["id"] ?: "", key), TeamCode.serializer()) }
    }
    delete("/v0/registry/teams/{id}/code") {
        call.write { key -> repo.revokeCode(call.parameters["id"] ?: "", key); call.reply(RegistryResult("revoked"), RegistryResult.serializer()) }
    }
    delete("/v0/registry/teams/{id}") {
        call.write { key -> repo.deleteTeam(call.parameters["id"] ?: "", key); call.reply(RegistryResult("deleted"), RegistryResult.serializer()) }
    }
    get("/v0/registry/teams/{id}/link-requests") {
        call.write { key -> call.reply(repo.inbox(call.parameters["id"] ?: "", key), ListSerializer(LinkRequestView.serializer())) }
    }
    get("/v0/registry/teams/{id}/links") {
        call.write { key -> call.reply(repo.links(call.parameters["id"] ?: "", key), ListSerializer(LinkView.serializer())) }
    }
    get("/v0/registry/teams/{id}/schedule") {
        call.write { key -> call.reply(repo.schedule(call.parameters["id"] ?: "", key), ListSerializer(CoordinatedFixture.serializer())) }
    }
    post("/v0/registry/teams/{teamCode}/link-requests") {
        val input = call.receive<LinkRequestInput>()
        call.write { key -> call.reply(repo.requestLink(call.parameters["teamCode"] ?: "", key, input), LinkRequestView.serializer()) }
    }
    post("/v0/registry/links/{requestId}/accept") {
        call.write { key -> call.reply(repo.acceptLink(call.parameters["requestId"] ?: "", key), LinkView.serializer()) }
    }
    delete("/v0/links/{linkId}") {
        call.write { key -> repo.revokeLink(call.parameters["linkId"] ?: "", key); call.reply(RegistryResult("revoked"), RegistryResult.serializer()) }
    }
    get("/v0/links/{linkId}/fixtures") {
        call.write { key -> call.reply(repo.linkFixtures(call.parameters["linkId"] ?: "", key), ListSerializer(CoordinatedFixture.serializer())) }
    }
    post("/v0/links/{linkId}/fixtures") {
        val input = call.receive<FixtureProposal>()
        call.write { key -> call.reply(repo.propose(call.parameters["linkId"] ?: "", key, input), CoordinatedFixture.serializer()) }
    }
    put("/v0/links/{linkId}/fixtures/{id}") {
        val input = call.receive<FixtureProposal>()
        call.write { key -> call.reply(repo.revise(call.parameters["linkId"] ?: "", call.parameters["id"] ?: "", key, input), CoordinatedFixture.serializer()) }
    }
    post("/v0/links/{linkId}/fixtures/{id}/confirm") {
        call.write { key -> call.reply(repo.confirm(call.parameters["linkId"] ?: "", call.parameters["id"] ?: "", key), CoordinatedFixture.serializer()) }
    }
    // ---- M7 consensus scoring (docs/protocol.md §4) ----------------------------------------
    post("/v0/links/{linkId}/consensus/changes") {
        val input = call.receive<ConsensusSubmit>()
        call.write { key ->
            try { call.reply(repo.submitConsensus(call.parameters["linkId"] ?: "", key, input), ConsensusDecision.serializer()) }
            catch (_: IllegalStateException) { call.respond(HttpStatusCode.Conflict) }
        }
    }
    post("/v0/consensus/changes/{changeId}/accept") {
        val input = call.receive<ConsensusReview>()
        call.write { key ->
            val (linkId, _) = repo.findLinkForChange(call.parameters["changeId"] ?: "")
            call.reply(repo.reviewConsensus(linkId, call.parameters["changeId"] ?: "", key, ConsensusAction.ACCEPT, input.idempotencyKey), ConsensusDecision.serializer())
        }
    }
    post("/v0/consensus/changes/{changeId}/reject") {
        val input = call.receive<ConsensusReview>()
        call.write { key ->
            val (linkId, _) = repo.findLinkForChange(call.parameters["changeId"] ?: "")
            call.reply(repo.reviewConsensus(linkId, call.parameters["changeId"] ?: "", key, ConsensusAction.REJECT, input.idempotencyKey), ConsensusDecision.serializer())
        }
    }
    post("/v0/consensus/changes/{changeId}/withdraw") {
        val input = call.receive<ConsensusReview>()
        call.write { key ->
            val (linkId, _) = repo.findLinkForChange(call.parameters["changeId"] ?: "")
            call.reply(repo.reviewConsensus(linkId, call.parameters["changeId"] ?: "", key, ConsensusAction.WITHDRAW, input.idempotencyKey), ConsensusDecision.serializer())
        }
    }
    post("/v0/links/{linkId}/consensus/changes/{changeId}/accept") {
        val input = call.receive<ConsensusReview>()
        call.write { key -> call.reply(repo.reviewConsensus(call.parameters["linkId"] ?: "", call.parameters["changeId"] ?: "", key, ConsensusAction.ACCEPT, input.idempotencyKey), ConsensusDecision.serializer()) }
    }
    post("/v0/links/{linkId}/consensus/changes/{changeId}/reject") {
        val input = call.receive<ConsensusReview>()
        call.write { key -> call.reply(repo.reviewConsensus(call.parameters["linkId"] ?: "", call.parameters["changeId"] ?: "", key, ConsensusAction.REJECT, input.idempotencyKey), ConsensusDecision.serializer()) }
    }
    post("/v0/links/{linkId}/consensus/changes/{changeId}/withdraw") {
        val input = call.receive<ConsensusReview>()
        call.write { key -> call.reply(repo.reviewConsensus(call.parameters["linkId"] ?: "", call.parameters["changeId"] ?: "", key, ConsensusAction.WITHDRAW, input.idempotencyKey), ConsensusDecision.serializer()) }
    }
    post("/v0/links/{linkId}/consensus/whistle") {
        val input = call.receive<ConsensusWhistle>()
        call.write { key -> call.reply(repo.whistle(call.parameters["linkId"] ?: "", key, input), ConsensusSnapshot.serializer()) }
    }
    get("/v0/links/{linkId}/consensus") {
        call.write { key ->
            val matchId = call.request.queryParameters["matchId"] ?: throw IllegalArgumentException("matchId required")
            call.reply(repo.consensusSnapshot(call.parameters["linkId"] ?: "", matchId, key), ConsensusSnapshot.serializer())
        }
    }
    /** Record ledger export: every decision with its full audit trail, forever (accepted, rejected, withdrawn). */
    get("/v0/links/{linkId}/consensus/ledger") {
        call.write { key ->
            val matchId = call.request.queryParameters["matchId"] ?: throw IllegalArgumentException("matchId required")
            val snapshot = repo.consensusSnapshot(call.parameters["linkId"] ?: "", matchId, key)
            val text = snapshot.decisions.joinToString("\n") { d ->
                "${d.change.changeId}\t${d.change.action}\t${d.change.submittedBy}\t${d.state}\t" +
                    (d.resolvedAt?.let { "by ${d.actor}@$it" } ?: "awaiting opposing decision") +
                    "\tevent=${d.event.eventId}"
            }
            call.respondText(text.ifEmpty { "no consensus records" }, ContentType.Text.Plain)
        }
    }
}
