package com.infinityball.pitchpact.dto

import com.infinityball.pitchpact.domain.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class EnvelopeTest {

    @Test
    fun envelopeCarriesProtocolVersionAndType() {
        val env = Envelope(
            type = "health",
            idempotencyKey = "uuid-1",
            payload = HealthPayload(status = "ok"),
        )
        val wire = PitchPactJson.codec.encodeToString(Envelope.serializer(), env)
        val parsed = PitchPactJson.codec.parseToJsonElement(wire).jsonObject
        assertEquals("v0", parsed["protocol"]?.jsonPrimitive?.content)
        assertEquals("health", parsed["type"]?.jsonPrimitive?.content)
        // Round-trip.
        assertEquals(env, PitchPactJson.codec.decodeFromString(Envelope.serializer(), wire))
    }

    @Test
    fun goldenVectorIsStable() {
        // Golden wire vector: if serialization ever drifts, the server and the
        // two apps drift with it. Update docs/protocol.md when bumping protocol.
        val wire = PitchPactJson.codec.encodeToString(
            Envelope.serializer(),
            Envelope(
                type = "echo",
                idempotencyKey = "fixed-key",
                payload = EchoPayload(request = "ping"),
            ),
        )
        assertEquals(
            """{"protocol":"v0","type":"echo","idempotencyKey":"fixed-key","payload":{"type":"echo","request":"ping"}}""",
            wire,
        )
    }

    @Test
    fun scoreboardRoundTripsWithEvents() {
        val payload = ScoreboardPayload(
            matchId = "m1",
            homeScore = 2,
            awayScore = 1,
            events = listOf(
                com.infinityball.pitchpact.domain.LedgerEvent.Event(
                    eventId = "g1",
                    side = Side.HOME,
                    recordedAtEpochMillis = 1234,
                    payload = com.infinityball.pitchpact.domain.EventPayload.Goal(
                        side = Side.HOME, jerseyNumber = 9,
                    ),
                ),
            ),
        )
        val env = Envelope(type = "scoreboard", idempotencyKey = "k", payload = payload)
        val wire = PitchPactJson.codec.encodeToString(Envelope.serializer(), env)
        val back = PitchPactJson.codec.decodeFromString(Envelope.serializer(), wire)
        assertEquals(env, back)
        assertTrue(wire.contains("\"protocol\":\"v0\""))
    }
}
