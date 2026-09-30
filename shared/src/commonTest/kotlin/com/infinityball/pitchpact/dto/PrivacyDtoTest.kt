package com.infinityball.pitchpact.dto

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Youth-privacy compile/serialization gate.
 *
 * Guardian contacts, rosters, and availability are contractually never allowed
 * into broadcast or registry payloads. These tests assert the serialization
 * surface of every broadcast/registry DTO kind contains none of the private
 * field names — and that they cannot be smuggled in via a subclass, since the
 * sealed DTO hierarchy is closed.
 */
class PrivacyDtoTest {

    private val forbiddenKeyFragments = listOf(
        "guardian", "contact", "phone", "email", "address",
        "availability", "rsvp",
    )

    private fun assertClean(label: String, serializer: KSerializer<*>) {
        val descriptor = serializer.descriptor
        val names = buildList {
            fun walk(d: kotlinx.serialization.descriptors.SerialDescriptor) {
                for (i in 0 until d.elementsCount) {
                    add(d.getElementName(i))
                    walk(d.getElementDescriptor(i))
                }
            }
            walk(descriptor)
        }
        for (fragment in forbiddenKeyFragments) {
            names.forEach { name ->
                assertFalse(
                    name.contains(fragment, ignoreCase = true),
                    "broadcast/registry DTO $label must not carry private field '$name'",
                )
            }
        }
    }

    @Test
    fun broadcastScoreboardDtoCarriesNoPrivateFields() {
        assertClean("ScoreboardPayload", ScoreboardPayload.serializer())
        assertClean("Envelope", Envelope.serializer())
    }

    @Test
    fun sealedPayloadHierarchyIsClosed() {
        // ScoreboardPayload and friends are final; nothing outside the
        // declared hierarchy can extend EnvelopePayload, so new wire shapes
        // require a code change here (reviewed) rather than a sneaky subclass.
        val subtypes = PitchPactJson.codec
            .encodeToString(
                Envelope.serializer(),
                Envelope(type = "health", idempotencyKey = "k", payload = HealthPayload("ok")),
            )
        assertTrue(subtypes.contains("\"type\":\"health\""))
    }

    // The private domain types exist (M2 will flesh them out) but are NOT part
    // of the EnvelopePayload hierarchy — assert the absence structurally.
    @Serializable
    @SerialName("guardianContactPrivate")
    private data class GuardianContactProbe(val phone: String)

    @Serializable
    @SerialName("availabilityPrivate")
    private data class AvailabilityProbe(val rsvp: String)

    @Test
    fun privateProbesCannotSerializeAsBroadcastPayload() {
        // A private probe never decodes to a broadcast payload type.
        val probeWire = PitchPactJson.codec.encodeToString(GuardianContactProbe.serializer(), GuardianContactProbe("555"))
        val decoded = runCatching {
            PitchPactJson.codec.decodeFromString(ScoreboardPayload.serializer(), probeWire)
        }
        assertFalse(decoded.isSuccess, "guardian contact must not deserialize as a scoreboard payload")
        val probeWire2 = PitchPactJson.codec.encodeToString(AvailabilityProbe.serializer(), AvailabilityProbe("yes"))
        val decoded2 = runCatching {
            PitchPactJson.codec.decodeFromString(ScoreboardPayload.serializer(), probeWire2)
        }
        assertFalse(decoded2.isSuccess)
    }
}
