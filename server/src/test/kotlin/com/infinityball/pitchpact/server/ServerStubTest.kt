package com.infinityball.pitchpact.server

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerStubTest {

    @Test
    fun healthzReturnsV0Envelope() = testApplication {
        application { appModule() }
        val resp = client.get("/healthz")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("\"protocol\":\"v0\""), "health payload must carry protocol v0: $body")
        assertTrue(body.contains("\"type\":\"health\""))
        assertTrue(body.contains("\"status\":\"ok\""))
    }

    @Test
    fun echoRoundTripsSharedEnvelopeVerbatim() = testApplication {
        application { appModule() }
        val request =
            """{"protocol":"v0","type":"echo","idempotencyKey":"fixed-key","payload":{"type":"echo","request":"ping"}}"""
        val resp = client.post("/v0/echo") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(request, resp.bodyAsText())
    }

    @Test
    fun echoRejectsGarbage() = testApplication {
        application { appModule() }
        val resp = client.post("/v0/echo") {
            contentType(ContentType.Application.Json)
            setBody("""{"not":"an envelope"}""")
        }
        // Malformed envelope must not 5xx into undefined behavior.
        assertTrue(
            resp.status == HttpStatusCode.BadRequest || resp.status == HttpStatusCode.UnprocessableEntity,
            "expected 4xx for garbage body, got ${resp.status}",
        )
    }
}
