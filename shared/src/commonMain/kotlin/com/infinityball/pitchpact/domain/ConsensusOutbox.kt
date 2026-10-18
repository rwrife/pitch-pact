package com.infinityball.pitchpact.domain

import kotlinx.serialization.Serializable

/**
 * Durable, ordered queue of consensus writes (submit / accept / reject / withdraw / whistle)
 * so a sideline scorer keeps working with zero connectivity. Every entry already carries the
 * idempotency key inside [body], so replaying after a lost response is safe: the server
 * returns the original result. Transport and file storage stay native; the ordering and
 * failure rules live here.
 *
 * Rules (never simplify):
 *  - entries deliver strictly in enqueue order; a network failure stops the flush and keeps
 *    every undelivered entry;
 *  - a definitive server refusal (4xx) removes the entry from the queue but is KEPT in
 *    [refused] with the status code — never silently dropped;
 *  - enqueueing the same [OutboxEntry.id] twice is a no-op.
 */
@Serializable
data class OutboxEntry(val id: String, val path: String, val body: String)

@Serializable
data class OutboxRefusal(val entry: OutboxEntry, val status: Int)

@Serializable
data class ConsensusOutbox(
    val queued: List<OutboxEntry> = emptyList(),
    val refused: List<OutboxRefusal> = emptyList(),
) {
    fun enqueue(entry: OutboxEntry): ConsensusOutbox {
        require(entry.id.isNotBlank() && entry.body.isNotBlank() &&
            Regex("^/v0/links/[A-Za-z0-9_-]+/consensus(?:/changes(?:/[A-Za-z0-9_-]+/(?:accept|reject|withdraw))?|/whistle)$").matches(entry.path)) {
            "Outbox entry must target a link consensus mutation"
        }
        return if (queued.any { it.id == entry.id } || refused.any { it.entry.id == entry.id }) this
        else copy(queued = queued + entry)
    }

    /** The entry that must be delivered next, or null when the queue is empty. */
    fun head(): OutboxEntry? = queued.firstOrNull()

    /** Head was accepted by the server. */
    fun delivered(id: String): ConsensusOutbox {
        require(queued.firstOrNull()?.id == id) { "Only the head entry can be marked delivered" }
        return copy(queued = queued.drop(1))
    }

    /** Head received a definitive 4xx: keep it for review, unblock the queue. */
    fun refuse(id: String, status: Int): ConsensusOutbox {
        val head = queued.firstOrNull()
        require(head?.id == id) { "Only the head entry can be refused" }
        require(status in 400..499) { "Only definitive client errors are refusals" }
        return copy(queued = queued.drop(1), refused = refused + OutboxRefusal(head!!, status))
    }

    /** Human wording for the UI; the apps never invent their own. */
    fun summary(): String = when {
        queued.isEmpty() && refused.isEmpty() -> "All consensus changes delivered"
        else -> "${queued.size} waiting to send" + if (refused.isEmpty()) "" else " · ${refused.size} refused by server"
    }
}
