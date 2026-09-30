package com.infinityball.pitchpact

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
 * M1 proof screen: renders STATE DERIVED FROM THE SHARED KMP MODULE only
 * (ledger + consensus machine). The Compose layer computes nothing — per
 * project rules all business rules live in `shared/`.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MatchStateView()
                }
            }
        }
    }
}

@Composable
fun MatchStateView() {
    val state = sharedMatchState()
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Pitch Pact", style = MaterialTheme.typography.headlineMedium)
        Text("protocol ${state.protocolVersion}")
        Text("official ${state.official}")
        Text("pending ${state.pendingCount}")
        Text("ledger ${state.ledgerCount}")
    }
}

/**
 * Exercises the shared module end to end: appends a goal event, submits a
 * score change, has the OPPOSING side accept it, derives the official score
 * from consensus state. Everything below is shared-module invocation.
 */
data class SharedProofState(
    val protocolVersion: String,
    val official: String,
    val pendingCount: Int,
    val ledgerCount: Int,
)

fun sharedMatchState(): SharedProofState {
    val ledger = MatchLedger().apply {
        append(
            LedgerEvent.Event(
                eventId = "g1", side = Side.HOME, recordedAtEpochMillis = 0,
                payload = EventPayload.Goal(side = Side.HOME, jerseyNumber = 9),
            ),
        )
    }
    val change = ConsensusRecord(
        ScoreChange("c1", "m1", Side.HOME, "g1", ScoreChangeAction.ADD),
    )
    change.apply(Side.AWAY, ConsensusAction.ACCEPT)
    val score = ConsensusScoring.officialScore(ledger, listOf(change))
    return SharedProofState(
        protocolVersion = PROTOCOL_VERSION,
        official = score.render(),
        pendingCount = listOf(change).count { it.state == ConsensusState.PENDING },
        ledgerCount = ledger.events.size,
    )
}
