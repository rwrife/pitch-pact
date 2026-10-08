package com.infinityball.pitchpact.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which side of the fixture recorded an event.
 * Events are always attributed so the two-scorer consensus model can tell them apart.
 */
@Serializable
enum class Side {
    @SerialName("home") HOME,
    @SerialName("away") AWAY,
}

/**
 * Clock transitions tracked in the ledger.
 */
@Serializable
enum class ClockTransition {
    @SerialName("kickoff_first_half") KICKOFF_FIRST_HALF,
    @SerialName("halftime") HALFTIME,
    @SerialName("kickoff_second_half") KICKOFF_SECOND_HALF,
    @SerialName("stoppage_started") STOPPAGE_STARTED,
    @SerialName("full_time") FULL_TIME,
}

/**
 * Goal type for goal events. Own goals are attributed to the conceding side.
 */
@Serializable
enum class GoalType {
    @SerialName("open_play") OPEN_PLAY,
    @SerialName("penalty") PENALTY,
    @SerialName("set_piece") SET_PIECE,
    @SerialName("own_goal") OWN_GOAL,
}

/**
 * Card colors.
 */
@Serializable
enum class CardColor {
    @SerialName("yellow") YELLOW,
    @SerialName("red") RED,
}

/**
 * Append-only match event payloads. Corrections/withdrawals are appended as new
 * events (see [LedgerEvent.Correction]); nothing in the ledger is ever mutated.
 *
 * NOTE: this is match-fact data only. Guardian contacts, roster privacy fields,
 * and availability responses are NOT representable here and must never be
 * embedded into broadcast/registry DTOs (enforced by LedgerPrivacyTest).
 */
@Serializable
sealed class EventPayload {
    @Serializable
    @SerialName("clock")
    data class Clock(val transition: ClockTransition, val elapsedSeconds: Long) : EventPayload()

    @Serializable
    @SerialName("goal")
    data class Goal(
        val side: Side,
        val jerseyNumber: Int? = null,
        val assistJerseyNumber: Int? = null,
        val goalType: GoalType = GoalType.OPEN_PLAY,
    ) : EventPayload()

    @Serializable
    @SerialName("card")
    data class Card(val side: Side, val jerseyNumber: Int?, val color: CardColor) : EventPayload()

    @Serializable
    @SerialName("penalty_miss")
    data class PenaltyMiss(val side: Side, val jerseyNumber: Int) : EventPayload()

    @Serializable
    @SerialName("extra")
    data class Extra(val side: Side, val kind: ExtraKind) : EventPayload()

    @Serializable
    @SerialName("substitution")
    data class Substitution(
        val side: Side,
        val offJerseyNumber: Int,
        val onJerseyNumber: Int,
    ) : EventPayload()
}

/**
 * One entry in the append-only ledger.
 *
 * `eventId` is the idempotency key for replays: appending the same event twice
 * is a no-op (see `MatchLedger.append`). `correctsEventId` (when set on a
 * [Correction]) marks the event it supersedes for scoreboard derivation while
 * both rows remain in the ledger forever.
 */
@Serializable
sealed class LedgerEvent {
    abstract val eventId: String
    abstract val side: Side
    abstract val recordedAtEpochMillis: Long

    @Serializable
    @SerialName("event")
    data class Event(
        override val eventId: String,
        override val side: Side,
        override val recordedAtEpochMillis: Long,
        val payload: EventPayload,
    ) : LedgerEvent()

    /**
     * Append-only correction: marks [correctsEventId] as superseded by
     * [replacement] for scoreboard purposes. The corrected row is retained.
     */
    @Serializable
    @SerialName("correction")
    data class Correction(
        override val eventId: String,
        override val side: Side,
        override val recordedAtEpochMillis: Long,
        val correctsEventId: String,
        val replacement: Event,
    ) : LedgerEvent()
}

/**
 * In-memory projection of an append-only ledger. Construction/replay is
 * idempotent for duplicate replay. New rows preserve their supplied order;
 * conflicting content and invalid correction order are rejected explicitly.
 *
 * Single-threaded by contract: instances are only touched from the UI thread /
 * explicit calls (Kotlin/Native thread-safety rule for this repo).
 */
class MatchLedger {
    private val _events = mutableListOf<LedgerEvent>()

    /** Every appended row, in first-seen order, including corrected originals. */
    val events: List<LedgerEvent> get() = _events.toList()

    /**
     * Append an event. Returns true if it was new, false if the idempotency key
     * (`eventId`) was already present (replay → no-op).
     */
    fun append(event: LedgerEvent): Boolean {
        validate(event)
        _events.firstOrNull { it.eventId == event.eventId }?.let {
            require(it == event) { "Duplicate event ID has different content" }
            return false
        }
        require(_events.filterIsInstance<LedgerEvent.Correction>().none { it.replacement.eventId == event.eventId }) { "Event ID collides with a correction replacement" }
        if (event is LedgerEvent.Correction) {
            require(effectiveEvents().any { it.eventId == event.correctsEventId && it.side == event.side }) { "Missing correction target or side mismatch" }
            require(event.replacement.side == event.side)
            require(event.replacement.eventId != event.eventId)
            require(_events.none { it.eventId == event.replacement.eventId || (it is LedgerEvent.Correction && it.replacement.eventId == event.replacement.eventId) }) { "Replacement ID already exists" }
        }
        _events += event
        return true
    }

    /**
     * Replay a batch (e.g. from an offline queue). Order-preserving and
     * idempotent. Returns the number of rows actually appended.
     */
    fun replayAll(batch: List<LedgerEvent>): Int = batch.count { append(it) }

    /**
     * Scoreboard view: rows with corrections applied (superseded originals
     * replaced by their replacements). The full [events] ledger is untouched.
     */
    fun effectiveEvents(): List<LedgerEvent> {
        val effective = linkedMapOf<String, LedgerEvent.Event>()
        for (event in _events) when (event) {
            is LedgerEvent.Event -> effective[event.eventId] = event
            is LedgerEvent.Correction -> {
                effective.remove(event.correctsEventId)
                effective[event.replacement.eventId] = event.replacement
            }
        }
        return effective.values.toList()
    }
}

private fun validate(event: LedgerEvent) {
    require(event.eventId.isNotBlank() && event.recordedAtEpochMillis >= 0)
    if (event is LedgerEvent.Correction) {
        require(event.correctsEventId.isNotBlank() && event.correctsEventId != event.eventId)
        validate(event.replacement)
        return
    }
    val e = event as LedgerEvent.Event
    fun jersey(n: Int?) { require(n == null || n >= 0) }
    when (val p = e.payload) {
        is EventPayload.Clock -> require(p.elapsedSeconds >= 0)
        is EventPayload.Goal -> { require(p.side == e.side); jersey(p.jerseyNumber); jersey(p.assistJerseyNumber) }
        is EventPayload.Card -> { require(p.side == e.side); jersey(p.jerseyNumber) }
        is EventPayload.Substitution -> { require(p.side == e.side); jersey(p.offJerseyNumber); jersey(p.onJerseyNumber); require(p.offJerseyNumber != p.onJerseyNumber) }
        is EventPayload.PenaltyMiss -> { require(p.side == e.side); jersey(p.jerseyNumber) }
        is EventPayload.Extra -> require(p.side == e.side)
    }
}
