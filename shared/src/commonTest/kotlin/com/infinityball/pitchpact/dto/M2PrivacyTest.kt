package com.infinityball.pitchpact.dto

import com.infinityball.pitchpact.domain.GuardianContact
import com.infinityball.pitchpact.domain.Player
import com.infinityball.pitchpact.domain.Team
import com.infinityball.pitchpact.domain.UniformRequirement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * M2 youth-privacy gate: the new private value types must NEVER leak into the
 * broadcast/registry serialization surface, and roster types must never carry
 * guardian fields. Fails the build on any leak — this is the "compile-time
 * asserted absent" acceptance criterion, enforced at the serialization
 * descriptor level (strongest surface reachable in Kotlin common code).
 */
class M2PrivacyTest {

    private val forbiddenFragments = listOf(
        "guardian", "phone", "email", "address", "contact",
    )

    private fun elementNames(serializer: kotlinx.serialization.KSerializer<*>): List<String> {
        val out = mutableListOf<String>()
        fun walk(d: kotlinx.serialization.descriptors.SerialDescriptor) {
            for (i in 0 until d.elementsCount) {
                out += d.getElementName(i)
                walk(d.getElementDescriptor(i))
            }
        }
        walk(serializer.descriptor)
        return out
    }

    @Test
    fun broadcastEnvelopeSubtypesContainNoPrivateKinds() {
        // EnvelopePayload is sealed: its descriptor enumerates EVERY wire kind
        // the server/broadcast layer can ever carry.
        val subtypes = elementNames(EnvelopePayload.serializer())
        assertTrue(subtypes.isNotEmpty())
        for (t in subtypes) {
            forbiddenFragments.forEach { f ->
                assertFalse(t.contains(f, true), "wire payload subtype '$t' must not exist")
            }
        }
    }

    @Test
    fun broadcastDtoDescriptorsCarryNoGuardianFieldNames() {
        val checked = mapOf(
            "Envelope" to Envelope.serializer(),
            "ScoreboardPayload" to ScoreboardPayload.serializer(),
        )
        for ((label, ser) in checked) {
            elementNames(ser).forEach { n ->
                forbiddenFragments.forEach { f ->
                    assertFalse(n.contains(f, true), "$label leaked private field '$n'")
                }
            }
        }
    }

    @Test
    fun rosterTypesCarryNoGuardianFields() {
        // Structural separation: Player/Team wire JSON contains no guardian
        // keys, so roster serialization can never smuggle contacts.
        for (n in elementNames(Player.serializer()) +
            elementNames(Team.serializer()) +
            elementNames(UniformRequirement.serializer())) {
            forbiddenFragments.forEach { f ->
                assertFalse(n.contains(f, true), "roster DTO leaked '$n'")
            }
        }
    }

    @Test
    fun guardianContactCannotDecodeAsAnyBroadcastPayload() {
        val wire = PitchPactJson.codec.encodeToString(
            GuardianContact.serializer(),
            GuardianContact("g1", "p1", "Sam Guardian", phone = "555-0100", email = "s@x.y"),
        )
        // The guardian row IS its own shape...
        val json = PitchPactJson.codec.parseToJsonElement(wire).jsonObject
        assertEquals("555-0100", json["phone"]!!.jsonPrimitive.content)
        // ...but it is NOT a valid EnvelopePayload discriminator value.
        val smuggle = """{"protocol":"v0","type":"guardianContact","idempotencyKey":"k","payload":$wire}"""
        assertFalse(
            runCatching { PitchPactJson.codec.decodeFromString(Envelope.serializer(), smuggle) }.isSuccess,
            "guardian payload must not decode as an envelope",
        )
        assertFalse(
            runCatching { PitchPactJson.codec.decodeFromString(ScoreboardPayload.serializer(), wire) }.isSuccess,
        )
    }

    @Test
    fun scoreboardSnapshotOfM2TeamsCarriesOnlyMatchFacts() {
        // Even with M2 entities around, the broadcast snapshot type is
        // unchanged: scoreboard wire JSON has zero team/roster/guardian keys.
        val snapshot = ScoreboardPayload("m1", 1, 0)
        val wire = PitchPactJson.codec.encodeToString(ScoreboardPayload.serializer(), snapshot)
        val obj: JsonElement = PitchPactJson.codec.parseToJsonElement(wire)
        val keys = (obj as JsonObject).keys.map { it.lowercase() }
        assertTrue(keys.none { k -> forbiddenFragments.any { k.contains(it) } || k == "team" || k == "roster" })
    }
}
