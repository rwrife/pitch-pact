package com.infinityball.pitchpact

import com.infinityball.pitchpact.domain.*
import com.infinityball.pitchpact.dto.*

/** UI-thread bridge. The platform owns networking and secret storage, not scoring rules. */
class BroadcastFacade {
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun create(matchId: String, home: String, away: String, id: String): String =
        PitchPactJson.codec.encodeToString(BroadcastCreate.serializer(), BroadcastCreate(matchId, home, away, idempotencyKey = id))
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun update(json: String, seq: Long, id: String, uptime: Long, epoch: Long, boot: String): String {
        val state = MatchDayCodec.decode(json)
        val decisions = state.decisions.toMutableList()
        for (row in state.events) {
            val goal = (row as? LedgerEvent.Event)?.payload is EventPayload.Goal
            val correction = row is LedgerEvent.Correction
            if ((goal || correction) && decisions.none { it.change.ledgerEventId == row.eventId }) {
                decisions += StoredScoreDecision(ScoreChange("broadcast-${row.eventId}", state.matchId, row.side, row.eventId,
                    if (correction) ScoreChangeAction.CORRECT else ScoreChangeAction.ADD), ConsensusState.PENDING, row.side)
            }
        }
        // The transmitted clock is a checkpoint, not a device uptime/boot identifier.
        val clock = state.clock.checkpoint(ClockSample(uptime, epoch, boot)).copy(anchor = null)
        return PitchPactJson.codec.encodeToString(BroadcastUpdate.serializer(), BroadcastUpdate(seq, id, state.events, decisions, clock))
    }
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun summary(json: String): String {
        val view = PitchPactJson.codec.decodeFromString(SpectatorView.serializer(), json)
        return "${view.homeTeam} vs ${view.awayTeam}\nOFFICIAL ${view.officialScore.homeScore}-${view.officialScore.awayScore}\n" +
            "${view.clock.phase} · ${view.clock.elapsedMillis / 60000}:${((view.clock.elapsedMillis / 1000) % 60).toString().padStart(2, '0')}\n" +
            "${view.pendingChanges.size} pending review · ${view.spectatorCount} watching"
    }
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun events(json: String): String {
        val view = PitchPactJson.codec.decodeFromString(SpectatorView.serializer(), json)
        return view.events.joinToString("\n") { row -> when (row) {
            is LedgerEvent.Correction -> "${row.side}: correction of ${row.correctsEventId} (retained)"
            is LedgerEvent.Event -> "${row.side}: ${when (val p = row.payload) {
                is EventPayload.Goal -> "${p.goalType} goal #${p.jerseyNumber ?: "?"} · pending unless accepted"
                is EventPayload.Card -> "${p.color} card #${p.jerseyNumber ?: "?"}"
                is EventPayload.Clock -> p.transition.name
                is EventPayload.Substitution -> "Sub #${p.offJerseyNumber} off / #${p.onJerseyNumber} on"
                is EventPayload.PenaltyMiss -> "Penalty miss #${p.jerseyNumber}"
                is EventPayload.Extra -> p.kind.name
            }}"
        } }
    }
}
