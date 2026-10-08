package com.infinityball.pitchpact.server

import com.infinityball.pitchpact.dto.Envelope
import com.infinityball.pitchpact.dto.HealthPayload
import com.infinityball.pitchpact.dto.PitchPactJson
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/**
 * M1 server stub: health probe + DTO envelope echo, reusing the shared
 * kotlinx.serialization DTOs verbatim (one definition, three consumers).
 *
 * Hosted deployment (pitchpact.infinityball.com) is a separate, user-owned
 * step — this module ships the container, nothing more.
 */
fun Application.appModule(
    matchRepository: MatchRepository? = null,
    authorize: (String, String?) -> Boolean = { _, _ -> false },
) {
    install(ContentNegotiation) {
        json(PitchPactJson.codec)
    }
    routing {
        post("/v0/matches/{id}/events") {
            val id = call.parameters["id"] ?: ""
            if (!authorize(id, call.request.headers["Authorization"])) {
                call.respond(io.ktor.http.HttpStatusCode.Forbidden); return@post
            }
            val repository = matchRepository
            if (repository == null) { call.respond(io.ktor.http.HttpStatusCode.ServiceUnavailable); return@post }
            try {
                val request = call.receive<com.infinityball.pitchpact.domain.MatchSyncRequest>()
                require(request.matchId == id)
                call.respond(repository.receive(request))
            } catch (e: IllegalArgumentException) {
                call.respond(io.ktor.http.HttpStatusCode.Conflict, "Invalid or conflicting match facts")
            }
        }
        get("/healthz") {
            // Raw wire text keeps the protocol field exactly as the shared
            // codec produces it (no server-side reinterpretation).
            val env = Envelope(type = "health", idempotencyKey = "server", payload = HealthPayload(status = "ok"))
            call.respondText(
                PitchPactJson.codec.encodeToString(Envelope.serializer(), env),
                contentType = ContentType.Application.Json,
            )
        }
        post("/v0/echo") {
            val incoming = call.receive<Envelope>()
            // Echo proves round-trip compat of the shared DTO definitions.
            val reply = incoming.copy(type = "echo", payload = incoming.payload)
            call.respondText(
                PitchPactJson.codec.encodeToString(Envelope.serializer(), reply),
                contentType = ContentType.Application.Json,
            )
        }
    }
}

fun main(args: Array<String>) {
    val port = (System.getenv("PORT") ?: args.getOrNull(0)?.takeIf { it.toIntOrNull() != null } ?: "8080").toInt()
    embeddedServer(Netty, port = port) {
        val matchId = System.getenv("MATCH_ID")
        val capability = System.getenv("MATCH_CAPABILITY")
        val directory = System.getenv("MATCH_DATA_DIRECTORY")
        appModule(directory?.let { MatchRepository(java.nio.file.Path.of(it)) }) { id, header ->
            matchId != null && capability != null && capability.length >= 32 && id == matchId && header == "Bearer $capability"
        }
    }.start(wait = true)
}
