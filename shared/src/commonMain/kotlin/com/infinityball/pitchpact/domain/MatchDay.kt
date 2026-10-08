package com.infinityball.pitchpact.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable enum class MatchPhase { PRE_KICKOFF, FIRST_HALF, HALFTIME, SECOND_HALF, STOPPAGE, FULL_TIME }
@Serializable data class ClockSample(val monotonicMillis: Long, val epochMillis: Long, val bootId: String) {
    init { require(monotonicMillis >= 0 && epochMillis >= 0 && bootId.isNotBlank()) }
}
@Serializable data class MatchClock(
    val phase: MatchPhase = MatchPhase.PRE_KICKOFF,
    val elapsedMillis: Long = 0,
    val anchor: ClockSample? = null,
    val stoppageHalf: Int = 2,
) {
    init { require(elapsedMillis >= 0 && stoppageHalf in 1..2) }
    fun elapsed(now: ClockSample): Long {
        require(now.monotonicMillis >= 0 && now.epochMillis >= 0 && now.bootId.isNotBlank())
        val running = phase in listOf(MatchPhase.FIRST_HALF, MatchPhase.SECOND_HALF, MatchPhase.STOPPAGE)
        val old = anchor ?: return elapsedMillis
        if (!running) return elapsedMillis
        // Never reuse uptime across boots. Wall time correlation is explicit and refuses backward time.
        val delta = if (old.bootId == now.bootId) now.monotonicMillis - old.monotonicMillis
            else now.epochMillis - old.epochMillis
        if (old.bootId != now.bootId) {
            require(now.epochMillis - now.monotonicMillis > old.epochMillis) {
                "Reboot epoch correlation is uncertain; confirm elapsed time manually"
            }
        }
        require(delta >= 0 && delta <= Long.MAX_VALUE - elapsedMillis) { "Clock correlation moved backwards; manual clock recovery required" }
        return elapsedMillis + delta
    }
    fun recover(now: ClockSample, confirmedElapsedMillis: Long): MatchClock {
        require(confirmedElapsedMillis >= elapsedMillis)
        require(now.monotonicMillis >= 0 && now.epochMillis >= 0 && now.bootId.isNotBlank())
        return copy(elapsedMillis = confirmedElapsedMillis, anchor = now)
    }
    fun checkpoint(now: ClockSample) = copy(elapsedMillis = elapsed(now), anchor = now)
    fun advance(now: ClockSample): MatchClock {
        val next = when (phase) {
            MatchPhase.PRE_KICKOFF -> MatchPhase.FIRST_HALF
            MatchPhase.FIRST_HALF -> MatchPhase.HALFTIME
            MatchPhase.HALFTIME -> MatchPhase.SECOND_HALF
            MatchPhase.SECOND_HALF -> MatchPhase.STOPPAGE
            MatchPhase.STOPPAGE -> if (stoppageHalf == 1) MatchPhase.HALFTIME else MatchPhase.FULL_TIME
            MatchPhase.FULL_TIME -> error("Match finished")
        }
        return checkpoint(now).copy(phase = next, stoppageHalf = if (phase == MatchPhase.SECOND_HALF) 2 else stoppageHalf)
    }
    fun stoppage(now: ClockSample): MatchClock {
        require(phase == MatchPhase.FIRST_HALF || phase == MatchPhase.SECOND_HALF)
        return checkpoint(now).copy(phase = MatchPhase.STOPPAGE, stoppageHalf = if (phase == MatchPhase.FIRST_HALF) 1 else 2)
    }
}

/** One atomic platform-store value: ledger, durable outbox and clock cannot tear. UI thread only. */
@Serializable data class StoredScoreDecision(val change: ScoreChange, val state: ConsensusState, val actor: Side)

@Serializable data class MatchDayState(
    val matchId: String,
    val events: List<LedgerEvent> = emptyList(),
    val queuedIds: List<String> = emptyList(),
    val submittedIds: Set<String> = emptySet(),
    val clock: MatchClock = MatchClock(),
    val decisions: List<StoredScoreDecision> = emptyList(),
    val enabledExtras: Set<ExtraKind> = ExtraKind.entries.toSet(),
) {
    fun capture(event: LedgerEvent): MatchDayState {
        require(matchId.isNotBlank())
        val ledger = MatchLedger().apply { replayAll(this@MatchDayState.events) }
        if (event is LedgerEvent.Event && event.payload is EventPayload.Extra)
            require(event.payload.kind in enabledExtras)
        return if (ledger.append(event)) copy(events = ledger.events, queuedIds = queuedIds + event.eventId) else this
    }
    fun undoTail(): MatchDayState {
        val tail = events.lastOrNull() ?: return this
        require(tail.eventId in queuedIds && tail.eventId !in submittedIds) { "Committed events require an appended correction" }
        require(decisions.none { it.change.ledgerEventId == tail.eventId }) { "Reviewed events require a correction" }
        require(tail !is LedgerEvent.Event || tail.payload !is EventPayload.Clock) { "Clock transitions are retained; use explicit clock recovery" }
        require(tail !is LedgerEvent.Correction) { "Corrections are retained forever" }
        return copy(events = events.dropLast(1), queuedIds = queuedIds - tail.eventId)
    }
    fun prepareSync(): MatchDayState = copy(submittedIds = submittedIds + queuedIds)
    fun acknowledge(receipt: List<LedgerEvent>): MatchDayState {
        require(receipt.all { received -> events.any { it == received } }) { "Unverified receipt" }
        return copy(queuedIds = queuedIds - receipt.map { it.eventId }.toSet())
    }
    fun officialScore(): OfficialScore {
        val ledger = MatchLedger().apply { replayAll(this@MatchDayState.events) }
        require(decisions.map { it.change.changeId }.distinct().size == decisions.size)
        val records = decisions.map { decision ->
            require(decision.change.matchId == matchId)
            ConsensusRecord(decision.change).also { record ->
                val action = when (decision.state) {
                    ConsensusState.ACCEPTED -> ConsensusAction.ACCEPT
                    ConsensusState.REJECTED -> ConsensusAction.REJECT
                    ConsensusState.WITHDRAWN -> ConsensusAction.WITHDRAW
                    ConsensusState.PENDING -> null
                }
                if (action != null) require(record.apply(decision.actor, action).allowed)
            }
        }
        return ConsensusScoring.officialScore(ledger, records)
    }
    fun pendingScore(): OfficialScore {
        val ledger = MatchLedger().apply { replayAll(this@MatchDayState.events) }
        val goals = ledger.effectiveEvents().filterIsInstance<LedgerEvent.Event>().mapNotNull { it.payload as? EventPayload.Goal }
        return OfficialScore(goals.count { beneficiary(it) == Side.HOME }, goals.count { beneficiary(it) == Side.AWAY })
    }
}
fun beneficiary(goal: EventPayload.Goal): Side = if (goal.goalType == GoalType.OWN_GOAL)
    if (goal.side == Side.HOME) Side.AWAY else Side.HOME else goal.side
@Serializable enum class ExtraKind { CORNER, OFFSIDE, SHOT_ON_TARGET, SAVE, INJURY_STOPPAGE }
object MatchDayCodec {
    val json = Json { encodeDefaults = true }
    fun encode(state: MatchDayState): String = json.encodeToString(MatchDayState.serializer(), state)
    fun decode(value: String): MatchDayState = json.decodeFromString(MatchDayState.serializer(), value).also {
        require(it.matchId.isNotBlank() && it.clock.elapsedMillis >= 0)
        require(it.clock.stoppageHalf in 1..2)
        it.clock.anchor?.let { anchor -> require(anchor.monotonicMillis >= 0 && anchor.epochMillis >= 0 && anchor.bootId.isNotBlank()) }
        require(MatchLedger().replayAll(it.events) == it.events.size)
        require(it.submittedIds.all { id -> it.events.any { e -> e.eventId == id } })
        it.officialScore()
        require(it.queuedIds.distinct().size == it.queuedIds.size && it.queuedIds.all { id -> it.events.any { e -> e.eventId == id } })
    }
}
