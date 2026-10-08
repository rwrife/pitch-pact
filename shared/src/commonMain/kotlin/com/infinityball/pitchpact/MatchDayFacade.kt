package com.infinityball.pitchpact

import com.infinityball.pitchpact.domain.*

/** Swift bridge; called exclusively on the native UI thread. */
class MatchDayFacade {
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun initial(id: String): String = MatchDayCodec.encode(MatchDayState(id))
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun clock(json: String, uptime: Long, epoch: Long, boot: String): String {
        val state = MatchDayCodec.decode(json)
        val seconds = state.clock.elapsed(ClockSample(uptime, epoch, boot)) / 1000
        return "${state.clock.phase}: ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun advance(json: String, uptime: Long, epoch: Long, boot: String, stoppage: Boolean, id: String, side: String): String {
        val state = MatchDayCodec.decode(json)
        val now = ClockSample(uptime, epoch, boot)
        val clock = if (stoppage) state.clock.stoppage(now) else state.clock.advance(now)
        val transition = when (clock.phase) {
            MatchPhase.FIRST_HALF -> ClockTransition.KICKOFF_FIRST_HALF
            MatchPhase.HALFTIME -> ClockTransition.HALFTIME
            MatchPhase.SECOND_HALF -> ClockTransition.KICKOFF_SECOND_HALF
            MatchPhase.STOPPAGE -> ClockTransition.STOPPAGE_STARTED
            MatchPhase.FULL_TIME -> ClockTransition.FULL_TIME
            MatchPhase.PRE_KICKOFF -> error("Invalid transition")
        }
        return MatchDayCodec.encode(state.copy(clock = clock).capture(
            LedgerEvent.Event(id, Side.valueOf(side), epoch, EventPayload.Clock(transition, clock.elapsedMillis / 1000))))
    }
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun capture(json: String, id: String, side: String, epoch: Long, kind: String, jersey: Int, assist: Int, other: Int): String {
        require(jersey >= 0 && assist >= -1 && other >= 0)
        val team = Side.valueOf(side)
        val payload = when (kind) {
            "PENALTY_MISS" -> EventPayload.PenaltyMiss(team, jersey)
            "YELLOW", "RED" -> EventPayload.Card(team, jersey, if (kind == "YELLOW") CardColor.YELLOW else CardColor.RED)
            "SUBSTITUTION" -> EventPayload.Substitution(team, jersey, other)
            else -> GoalType.entries.firstOrNull { it.name == kind }?.let {
                EventPayload.Goal(team, jersey, if (assist == -1) null else assist, it)
            } ?: EventPayload.Extra(team, ExtraKind.valueOf(kind))
        }
        return MatchDayCodec.encode(MatchDayCodec.decode(json).capture(LedgerEvent.Event(id, team, epoch, payload)))
    }
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun official(json: String): String = MatchDayCodec.decode(json).officialScore().render()
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun pending(json: String): String = MatchDayCodec.decode(json).pendingScore().render()
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun undo(json: String): String = MatchDayCodec.encode(MatchDayCodec.decode(json).undoTail())
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun correct(json: String, target: String, replacementJSON: String, id: String, epoch: Long): String {
        val state = MatchDayCodec.decode(json)
        val replacement = MatchDayCodec.decode(replacementJSON).events.last() as LedgerEvent.Event
        return MatchDayCodec.encode(state.capture(LedgerEvent.Correction(id, replacement.side, epoch, target, replacement)))
    }
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun extras(json: String, enabled: String): String = MatchDayCodec.encode(MatchDayCodec.decode(json).copy(
        enabledExtras = enabled.split(",").filter { it.isNotBlank() }.map { ExtraKind.valueOf(it) }.toSet()))
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun request(json: String): String {
        val state = MatchDayCodec.decode(json)
        return MatchDayCodec.json.encodeToString(MatchSyncRequest.serializer(), MatchSyncRequest(state.matchId, state.events.filter { it.eventId in state.queuedIds }))
    }
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun receipt(json: String, requestJSON: String, receiptJSON: String): String {
        val request = MatchDayCodec.json.decodeFromString(MatchSyncRequest.serializer(), requestJSON)
        val receipt = MatchDayCodec.json.decodeFromString(MatchSyncReceipt.serializer(), receiptJSON)
        val state = MatchDayCodec.decode(json)
        require(receipt.matchId == state.matchId && receipt.matchId == request.matchId && receipt.events == request.events)
        return MatchDayCodec.encode(state.acknowledge(receipt.events))
    }
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun prepareSync(json: String): String = MatchDayCodec.encode(MatchDayCodec.decode(json).prepareSync())
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun recoverClock(json: String, uptime: Long, epoch: Long, boot: String, elapsed: Long): String {
        val state = MatchDayCodec.decode(json)
        return MatchDayCodec.encode(state.copy(clock = state.clock.recover(ClockSample(uptime, epoch, boot), elapsed)))
    }
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun audit(json: String): String {
        fun describe(event: LedgerEvent.Event): String {
            val side = event.side.name.lowercase().replaceFirstChar { it.uppercase() }
            return when (val payload = event.payload) {
                is EventPayload.Goal -> "$side goal #${payload.jerseyNumber ?: "?"} (${payload.goalType.name.lowercase().replace('_', ' ')})" +
                    (payload.assistJerseyNumber?.let { " assist #$it" } ?: "")
                is EventPayload.Card -> "$side ${payload.color.name.lowercase()} card #${payload.jerseyNumber ?: "?"}"
                is EventPayload.Substitution -> "$side substitution #${payload.offJerseyNumber} off, #${payload.onJerseyNumber} on"
                is EventPayload.PenaltyMiss -> "$side penalty miss #${payload.jerseyNumber}"
                is EventPayload.Extra -> "$side ${payload.kind.name.lowercase().replace('_', ' ')}"
                is EventPayload.Clock -> "$side clock ${payload.transition.name.lowercase().replace('_', ' ')} at ${payload.elapsedSeconds}s"
            }
        }
        return MatchDayCodec.decode(json).events.joinToString("\n") { event ->
            when (event) {
                is LedgerEvent.Event -> "${event.eventId}: ${describe(event)}"
                is LedgerEvent.Correction -> "${event.eventId}: correction of ${event.correctsEventId}: ${describe(event.replacement)} (replacement ${event.replacement.eventId})"
            }
        }
    }
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun captureInput(json: String, id: String, side: String, epoch: Long, kind: String, jersey: String, assist: String, other: String): String {
        val assisted = if (assist.isBlank()) -1 else assist.toInt().also { require(it >= 0) }
        return capture(json, id, side, epoch, kind, jersey.toInt(), assisted, other.toInt())
    }
}
