package com.infinityball.pitchpact.dto

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlin.test.*

class M6PrivacyTest {
    private val forbiddenWords = listOf("guardian", "availability", "roster", "playerId", "contact", "email", "phone", "address")
    private fun inspect(descriptor: SerialDescriptor, visited: MutableSet<String> = mutableSetOf()) {
        if (!visited.add(descriptor.serialName)) return
        for (index in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(index)
            forbiddenWords.forEach {
                assertFalse(name.contains(it, ignoreCase = true), "Private field $name in ${descriptor.serialName}")
            }
            inspect(descriptor.getElementDescriptor(index), visited)
        }
    }

    @Test fun registryVisibleFieldsAreExactlyThree() {
        // Contractual: only clubName, region, ageGroup are exposed to registry / search.
        val descriptor = RegistryListing.serializer().descriptor
        val names = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
        assertEquals(listOf("clubName", "region", "ageGroup"), names)
    }

    @Test fun coordinationWireContainsNoPrivateData() {
        inspect(RegistryListing.serializer().descriptor)
        inspect(RegistryCreate.serializer().descriptor)
        inspect(RegistryTeam.serializer().descriptor)
        inspect(RegistryCredentials.serializer().descriptor)
        inspect(TeamCode.serializer().descriptor)
        inspect(LinkRequestInput.serializer().descriptor)
        inspect(LinkRequestView.serializer().descriptor)
        inspect(LinkView.serializer().descriptor)
        inspect(CoordinatedFixture.serializer().descriptor)
        inspect(FixtureProposal.serializer().descriptor)
    }
}
