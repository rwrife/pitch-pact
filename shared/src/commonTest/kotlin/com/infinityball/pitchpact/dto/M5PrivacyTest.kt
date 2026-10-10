package com.infinityball.pitchpact.dto
import com.infinityball.pitchpact.domain.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlin.test.*

class M5PrivacyTest {
    private fun inspect(descriptor: SerialDescriptor, visited: MutableSet<String> = mutableSetOf()) {
        if (!visited.add(descriptor.serialName)) return
        for (index in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(index)
            listOf("guardian", "availability", "roster", "playerId", "contact", "email", "phone", "address").forEach {
                assertFalse(name.contains(it, ignoreCase = true), "Private field $name in ${descriptor.serialName}")
            }
            inspect(descriptor.getElementDescriptor(index), visited)
        }
    }
    @Test fun broadcastWireContainsOnlyMatchFacts() {
        inspect(BroadcastCreate.serializer().descriptor)
        inspect(BroadcastStarted.serializer().descriptor)
        inspect(BroadcastUpdate.serializer().descriptor)
        inspect(BroadcastAck.serializer().descriptor)
        inspect(SpectatorView.serializer().descriptor)
    }
}
