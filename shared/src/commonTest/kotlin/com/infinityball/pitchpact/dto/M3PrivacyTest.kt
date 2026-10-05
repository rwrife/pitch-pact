package com.infinityball.pitchpact.dto

import com.infinityball.pitchpact.domain.AvailabilityResponse
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFails

class M3PrivacyTest {
    private fun assertPrivateAbsent(descriptor: SerialDescriptor) {
        for (index in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(index)
            listOf("availability", "rsvp", "playerId", "guardian", "roster").forEach {
                assertFalse(name.contains(it, ignoreCase = true), "public DTO contains $name")
            }
            assertPrivateAbsent(descriptor.getElementDescriptor(index))
        }
    }

    @Test fun publicWireDescriptorsExcludeAvailability() {
        assertPrivateAbsent(Envelope.serializer().descriptor)
        assertPrivateAbsent(EnvelopePayload.serializer().descriptor)
        assertPrivateAbsent(ScoreboardPayload.serializer().descriptor)
    }

    @Test fun privateRsvpCannotDecodeAsBroadcast() {
        val privateJson = """{"fixtureId":"f","playerId":"p","choice":"YES"}"""
        // A serializer exists for LOCAL persistence, but not a public envelope subtype.
        kotlinx.serialization.json.Json.decodeFromString(AvailabilityResponse.serializer(), privateJson)
        assertFails { PitchPactJson.codec.decodeFromString(Envelope.serializer(),
            """{"protocol":"v0","type":"availability","idempotencyKey":"k","payload":$privateJson}""") }
    }
}
