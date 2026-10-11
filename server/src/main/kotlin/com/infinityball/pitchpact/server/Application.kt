package com.infinityball.pitchpact.server

import com.infinityball.pitchpact.dto.*
import com.infinityball.pitchpact.domain.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.nio.file.Path

fun Application.appModule(
    matchRepository: MatchRepository? = null,
    broadcastRepository: BroadcastRepository? = null,
    traffic: BroadcastTraffic = BroadcastTraffic(),
    authorize: (String, String?) -> Boolean = { _, _ -> false },
) {
    install(ContentNegotiation) { json(PitchPactJson.codec) }
    routing {
        post("/v0/matches/{id}/events") {
            val id = call.parameters["id"] ?: ""
            if (!authorize(id, call.request.headers["Authorization"])) { call.respond(HttpStatusCode.Forbidden); return@post }
            val repository = matchRepository
            if (repository == null) { call.respond(HttpStatusCode.ServiceUnavailable); return@post }
            try {
                val request = call.receive<MatchSyncRequest>()
                require(request.matchId == id)
                call.respond(repository.receive(request))
            } catch (_: IllegalArgumentException) { call.respond(HttpStatusCode.Conflict, "Invalid or conflicting match facts") }
        }
        post("/v0/broadcasts") {
            val repo = broadcastRepository ?: run { call.respond(HttpStatusCode.ServiceUnavailable); return@post }
            if (!traffic.allow("create", call.request.origin.remoteAddress, 20)) {
                call.respond(HttpStatusCode.TooManyRequests); return@post
            }
            try {
                val input = call.receive<BroadcastCreate>()
                if (!authorize(input.matchId, call.request.header("Authorization"))) { call.respond(HttpStatusCode.Forbidden); return@post }
                call.respond(repo.create(input))
            } catch (_: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest) }
        }
        patch("/v0/broadcasts/{code}") {
            val repo = broadcastRepository ?: run { call.respond(HttpStatusCode.ServiceUnavailable); return@patch }
            val key = call.request.header("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
            if (key == null) { call.respond(HttpStatusCode.Unauthorized); return@patch }
            if (!traffic.allow("write", key, 120)) { call.respond(HttpStatusCode.TooManyRequests); return@patch }
            val code = call.parameters["code"]?.uppercase() ?: ""
            try { call.respond(repo.update(code, key, call.receive<BroadcastUpdate>())) }
            catch (_: MissingBroadcast) { call.respond(HttpStatusCode.NotFound) }
            catch (_: UnauthorizedBroadcast) { call.respond(HttpStatusCode.Forbidden) }
            catch (_: IllegalArgumentException) { call.respond(HttpStatusCode.Conflict) }
        }
        delete("/v0/broadcasts/{code}") {
            val repo = broadcastRepository ?: run { call.respond(HttpStatusCode.ServiceUnavailable); return@delete }
            val key = call.request.header("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
            if (key == null) { call.respond(HttpStatusCode.Unauthorized); return@delete }
            try { repo.stop(call.parameters["code"]?.uppercase() ?: "", key); call.respond(HttpStatusCode.NoContent) }
            catch (_: MissingBroadcast) { call.respond(HttpStatusCode.NotFound) }
            catch (_: UnauthorizedBroadcast) { call.respond(HttpStatusCode.Forbidden) }
        }
        post("/v0/broadcasts/{code}/rotate") {
            val repo = broadcastRepository ?: run { call.respond(HttpStatusCode.ServiceUnavailable); return@post }
            val key = call.request.header("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
            if (key == null) { call.respond(HttpStatusCode.Unauthorized); return@post }
            try { call.respond(repo.rotate(call.parameters["code"]?.uppercase() ?: "", key)) }
            catch (_: MissingBroadcast) { call.respond(HttpStatusCode.NotFound) }
            catch (_: UnauthorizedBroadcast) { call.respond(HttpStatusCode.Forbidden) }
        }
        get("/v0/spectate/{code}") {
            val repo = broadcastRepository ?: run { call.respond(HttpStatusCode.ServiceUnavailable); return@get }
            val code = call.parameters["code"]?.uppercase() ?: ""
            val ip = call.request.origin.remoteAddress
            if (!traffic.allow("read:$code", ip, 120)) { call.respond(HttpStatusCode.TooManyRequests); return@get }
            try { call.respond(repo.spectate(code, traffic.count(code, ip))) }
            catch (_: MissingBroadcast) { call.respond(HttpStatusCode.NotFound) }
            catch (_: IllegalArgumentException) { call.respond(HttpStatusCode.NotFound) }
        }
        get("/spectate/{code}") {
            val repo = broadcastRepository ?: run { call.respond(HttpStatusCode.ServiceUnavailable); return@get }
            val code = call.parameters["code"]?.uppercase() ?: ""
            val ip = call.request.origin.remoteAddress
            if (!traffic.allow("read:$code", ip, 120)) { call.respond(HttpStatusCode.TooManyRequests); return@get }
            try { call.respondText(renderSpectatorHtml(code, repo.spectate(code, traffic.count(code, ip))), ContentType.Text.Html) }
            catch (_: MissingBroadcast) { call.respond(HttpStatusCode.NotFound) }
            catch (_: IllegalArgumentException) { call.respond(HttpStatusCode.NotFound) }
        }
        get("/") {
            val code = call.request.queryParameters["code"]?.uppercase()?.takeIf { it.matches(Regex("[A-Z0-9]{6}")) }
            if (code != null) call.respondRedirect("/spectate/$code")
            else call.respondText("""<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Pitch Pact · Watch</title><h1>Watch a match</h1><form><label>Six-character code <input name="code" required pattern="[A-Za-z0-9]{6}" maxlength="6" autocomplete="off"></label><button>Watch</button></form></html>""", ContentType.Text.Html)
        }
        get("/healthz") {
            val env = Envelope(type = "health", idempotencyKey = "server", payload = HealthPayload(status = "ok"))
            call.respondText(PitchPactJson.codec.encodeToString(Envelope.serializer(), env), contentType = ContentType.Application.Json)
        }
        post("/v0/echo") {
            val incoming = call.receive<Envelope>()
            call.respondText(PitchPactJson.codec.encodeToString(Envelope.serializer(), incoming.copy(type = "echo")), contentType = ContentType.Application.Json)
        }
    }
}

private fun html(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
private fun renderSpectatorHtml(code: String, view: SpectatorView): String {
    val events = view.events.joinToString("") { row ->
        val event = row as? LedgerEvent.Event
        val detail = when (val payload = event?.payload) {
            is EventPayload.Goal -> "Goal · #${payload.jerseyNumber ?: "?"}"
            is EventPayload.Card -> "${payload.color} card · #${payload.jerseyNumber ?: "?"}"
            is EventPayload.Clock -> "Clock · ${payload.transition}"
            is EventPayload.Substitution -> "Substitution · #${payload.offJerseyNumber} / #${payload.onJerseyNumber}"
            is EventPayload.PenaltyMiss -> "Penalty miss · #${payload.jerseyNumber}"
            is EventPayload.Extra -> payload.kind.name
            null -> "Correction"
        }
        "<li>${html(event?.side?.name ?: "")} · ${html(detail)}</li>"
    }
    return """<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><meta http-equiv="refresh" content="10"><title>Pitch Pact · ${html(code)}</title><style>body{font:1.1rem system-ui;max-width:40rem;margin:2rem auto;padding:1rem;background:#102034;color:#fff}section{background:#234;padding:1rem;border-radius:1rem;margin:1rem 0}strong{font-size:3rem}a{color:#9ef}</style><h1>${html(view.homeTeam)} vs ${html(view.awayTeam)}</h1><p>Code ${html(code)} · ${view.spectatorCount} watching · refreshes every 10 seconds</p><section><strong>${view.officialScore.homeScore} – ${view.officialScore.awayScore}</strong><p>${html(view.clock.phase.name)} · ${view.clock.elapsedMillis / 60000} minutes</p></section><section><h2>Pending review: ${view.pendingChanges.size}</h2><p>Pending goals do not count in the official score.</p></section><section><h2>Play by play</h2><ol>$events</ol></section><p><a href="/">Join another game</a></p></html>"""
}

fun main(args: Array<String>) {
    val port = (System.getenv("PORT") ?: args.getOrNull(0)?.takeIf { it.toIntOrNull() != null } ?: "8080").toInt()
    val directory = System.getenv("MATCH_DATA_DIRECTORY")
    val broadcastDirectory = System.getenv("BROADCAST_DATA_DIRECTORY") ?: "/data/broadcasts"
    val matchId = System.getenv("MATCH_ID")
    val capability = System.getenv("MATCH_CAPABILITY")
    embeddedServer(Netty, port = port) {
        appModule(directory?.let { MatchRepository(Path.of(it)) }, BroadcastRepository(Path.of(broadcastDirectory))) { id, header ->
            matchId != null && capability != null && capability.length >= 32 && id == matchId && header == "Bearer $capability"
        }
    }.start(wait = true)
}
