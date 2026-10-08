package com.infinityball.pitchpact.dto
import com.infinityball.pitchpact.domain.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlin.test.*

class M4PrivacyTest {
    private fun inspect(descriptor: SerialDescriptor, visited: MutableSet<String> = mutableSetOf()) {
        if (!visited.add(descriptor.serialName)) return
        for (index in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(index)
            listOf("guardian", "availability", "roster", "playerId", "contact", "name").forEach {
                assertFalse(name.contains(it, ignoreCase = true), "Private field $name")
            }
            inspect(descriptor.getElementDescriptor(index), visited)
        }
    }
    @Test fun matchWireContainsOnlyFacts() {
        inspect(MatchSyncRequest.serializer().descriptor)
        inspect(MatchSyncReceipt.serializer().descriptor)
        inspect(EventPayload.Goal.serializer().descriptor)
        inspect(EventPayload.Card.serializer().descriptor)
        inspect(EventPayload.Substitution.serializer().descriptor)
    }
}
