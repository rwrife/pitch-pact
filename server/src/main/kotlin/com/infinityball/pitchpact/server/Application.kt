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
fun Application.appModule() {
    install(ContentNegotiation) {
        json(PitchPactJson.codec)
    }
    routing {
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
        appModule()
    }.start(wait = true)
}
