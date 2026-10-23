package com.infinityball.pitchpact.domain

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import com.infinityball.pitchpact.dto.PitchPactJson

class ConsensusOutboxTest {
    private val add = OutboxEntry("change-1", "/v0/links/L/consensus/changes", "{\"idempotencyKey\":\"key-1\"}")
    private val review = OutboxEntry("review-2", "/v0/links/L/consensus/changes/change-1/accept", "{\"idempotencyKey\":\"key-2\"}")

    @Test fun onlyMaturesConsensusMutationsMayBeQueued() {
        assertFailsWith<IllegalArgumentException> { ConsensusOutbox().enqueue(OutboxEntry("x", "/v0/links/L/consensus?matchId=M", "{}")) }
        assertFailsWith<IllegalArgumentException> { ConsensusOutbox().enqueue(OutboxEntry("x", "/v0/registry/teams/T", "{}")) }
    }

    @Test fun retryAfterLostResponsePreservesOrderAndIdempotencyKey() {
        val pending = ConsensusOutbox().enqueue(add).enqueue(review).enqueue(add)
        val restored = PitchPactJson.codec.decodeFromString<ConsensusOutbox>(PitchPactJson.codec.encodeToString(pending))
        assertEquals(2, restored.queued.size)
        assertEquals(add, restored.head())
        assertEquals(add, restored.head()) // network loss leaves exact request in place
        assertFailsWith<IllegalArgumentException> { restored.delivered(review.id) }
        assertEquals(review, restored.delivered(add.id).head())
        val delivered = restored.delivered(add.id).delivered(review.id)
        assertEquals("All consensus changes delivered", delivered.summary())
        assertEquals(delivered, delivered.enqueue(add)) // restart cannot re-submit delivered capture
        assertTrue(PitchPactJson.codec.decodeFromString<ConsensusOutbox>(PitchPactJson.codec.encodeToString(delivered)).contains(add.id))
        assertFalse(delivered.contains("never-sent"))
    }

    @Test fun definitiveRefusalRetainsAuditAndServerFailureNeverDiscardsWork() {
        val pending = ConsensusOutbox().enqueue(add).enqueue(review)
        assertFailsWith<IllegalArgumentException> { pending.refuse(add.id, 503) }
        val refused = pending.refuse(add.id, 409)
        assertEquals(review, refused.head())
        assertEquals(OutboxRefusal(add, 409), refused.refused.single())
        assertEquals("1 waiting to send · 1 refused by server", refused.summary())
        assertEquals(refused, refused.enqueue(add))
    }
}
