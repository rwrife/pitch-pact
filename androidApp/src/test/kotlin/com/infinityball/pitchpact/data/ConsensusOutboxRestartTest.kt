package com.infinityball.pitchpact.data

import androidx.test.core.app.ApplicationProvider
import com.infinityball.pitchpact.ConsensusFacade
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Restart/replay proof for the durable consensus outbox: a fresh process (new store +
 * facade instances, as after process death) must resume the exact bytes, order and
 * idempotency keys the pre-crash session queued.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConsensusOutboxRestartTest {
    @Test fun freshProcessResumesExactQueuedWritesInOrder() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val facade = ConsensusFacade()
        var queue = facade.outboxEnqueue(facade.outboxNew(), "a", "/v0/links/L/consensus/changes", """{"idempotencyKey":"k-a","jersey":9}""")
        queue = facade.outboxEnqueue(queue, "b", "/v0/links/L/consensus/changes", """{"idempotencyKey":"k-b","jersey":10}""")
        queue = facade.outboxEnqueue(queue, "c", "/v0/links/L/consensus/changes/a/accept", """{"idempotencyKey":"k-c"}""")
        queue = facade.outboxDelivered(queue, "a") // first write got its receipt before the crash
        ConsensusOutboxStore(context).save("https://srv", "L", "T", queue)

        // Process death: new store + facade instances; only the file survives.
        val facade2 = ConsensusFacade()
        val store2 = ConsensusOutboxStore(context)
        var resumed = store2.load("https://srv", "L", "T")!!
        assertEquals("2 waiting to send", facade2.outboxSummary(resumed))
        var head = facade2.outboxHead(resumed)
        assertEquals("b", facade2.outboxId(head))
        assertEquals("""{"idempotencyKey":"k-b","jersey":10}""", facade2.outboxBody(head))

        // Re-enqueueing the same id with different bytes must not replace the stored write.
        resumed = facade2.outboxEnqueue(resumed, "b", "/v0/links/L/consensus/changes", """{"idempotencyKey":"tampered"}""")
        assertEquals("""{"idempotencyKey":"k-b","jersey":10}""", facade2.outboxBody(facade2.outboxHead(resumed)))

        resumed = facade2.outboxDelivered(resumed, "b")
        head = facade2.outboxHead(resumed)
        assertEquals("c", facade2.outboxId(head))
        assertEquals("/v0/links/L/consensus/changes/a/accept", facade2.outboxPath(head))

        // Definitive refusal survives yet another restart and stays visible.
        resumed = facade2.outboxRefuse(resumed, "c", 409)
        store2.save("https://srv", "L", "T", resumed)
        val third = ConsensusOutboxStore(context).load("https://srv", "L", "T")!!
        assertEquals("0 waiting to send · 1 refused by server", ConsensusFacade().outboxSummary(third))
    }

    @Test fun missingQueueLoadsAsNullNotEmptyOverwrite() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNull(ConsensusOutboxStore(context).load("https://srv", "never-written", "T"))
    }
}
