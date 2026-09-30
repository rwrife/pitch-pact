package com.infinityball.pitchpact

import com.infinityball.pitchpact.domain.ConsensusAction
import com.infinityball.pitchpact.domain.ConsensusRecord
import com.infinityball.pitchpact.domain.ConsensusScoring
import com.infinityball.pitchpact.domain.ConsensusState
import com.infinityball.pitchpact.domain.EventPayload
import com.infinityball.pitchpact.domain.LedgerEvent
import com.infinityball.pitchpact.domain.MatchLedger
import com.infinityball.pitchpact.domain.ScoreChange
import com.infinityball.pitchpact.domain.ScoreChangeAction
import com.infinityball.pitchpact.domain.Side
import com.infinityball.pitchpact.dto.PROTOCOL_VERSION

/**
 * Platform-facing facade consumed from Swift (via the XCFramework) and from
 * Compose. It exercises the real domain logic — append a goal to the ledger,
 * submit a score change, have the opposing side accept it, derive the
 * official score — so both native shells prove the same KMP loop (M1).
 *
 * Single-threaded by contract: call from the main UI thread only.
 */
object KmpFacade {
    fun protocolVersion(): String = PROTOCOL_VERSION

    fun proofSummary(): String {
        val ledger = MatchLedger().apply {
            append(
                LedgerEvent.Event(
                    eventId = "g1",
                    side = Side.HOME,
                    recordedAtEpochMillis = 0,
                    payload = EventPayload.Goal(side = Side.HOME, jerseyNumber = 9),
                ),
            )
        }
        val change = ConsensusRecord(
            ScoreChange("c1", "m1", Side.HOME, "g1", ScoreChangeAction.ADD),
        )
        change.apply(Side.AWAY, ConsensusAction.ACCEPT)
        val official = ConsensusScoring.officialScore(ledger, listOf(change)).render()
        val pending = if (change.state == ConsensusState.PENDING) 1 else 0
        return "protocol $PROTOCOL_VERSION · official $official · pending $pending · ledger ${ledger.events.size}"
    }
}
