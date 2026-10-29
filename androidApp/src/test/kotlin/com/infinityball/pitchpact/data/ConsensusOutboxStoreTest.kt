package com.infinityball.pitchpact.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConsensusOutboxStoreTest {
    @Test fun persistsPerEndpointLinkTeamAndNeverMixesQueues() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ConsensusOutboxStore(context)
        store.save("https://a", "link", "team", "A")
        store.save("https://b", "link", "team", "B")
        store.save("https://a", "other", "team", "C")
        store.save("https://a", "link", "other", "D")
        assertEquals("A", store.load("https://a", "link", "team"))
        assertEquals("B", store.load("https://b", "link", "team"))
        assertEquals("C", store.load("https://a", "other", "team"))
        assertEquals("D", store.load("https://a", "link", "other"))
        assertNull(store.load("https://a", "link", "missing"))
    }
}
