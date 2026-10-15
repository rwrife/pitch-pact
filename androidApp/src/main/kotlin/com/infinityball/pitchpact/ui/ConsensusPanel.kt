package com.infinityball.pitchpact.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp

import com.infinityball.pitchpact.ConsensusFacade
import com.infinityball.pitchpact.dto.LinkView
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * M7 scorer panel for one team link. Pure transport + rendering: every side
 * mapping, wire body and label comes from shared ConsensusFacade. Official score
 * and the pending band are always rendered separately.
 */
@Composable
fun ConsensusPanel(client: RegistryClient, link: LinkView, myTeamId: String, ownerKey: String) {
    val facade = remember { ConsensusFacade() }
    val scope = rememberCoroutineScope()
    val side = remember(link.id, myTeamId) { facade.sideFor(link.firstTeamId, link.secondTeamId, myTeamId) }
    var matchId by remember { mutableStateOf("") }
    var jersey by remember { mutableStateOf("") }
    var snapshot by remember { mutableStateOf("") }
    var ledger by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    fun run(action: suspend () -> Unit) { scope.launch { try { action(); message = "" } catch (e: Exception) { message = e.message ?: "Consensus failed" } } }
    suspend fun refresh() {
        require(matchId.matches(Regex("[A-Za-z0-9:_-]{1,128}"))) { "Match ID must be 1-128 letters, digits, colon, underscore, or hyphen" }
        snapshot = client.raw("GET", "/v0/links/${link.id}/consensus?matchId=$matchId", ownerKey, null)
        ledger = facade.recordLedger(snapshot)
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Column(Modifier.padding(8.dp)) {
        Text("Consensus scoring · you are the ${side.lowercase()} sideline", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(matchId, { matchId = it.trim() }, label = { Text("Match ID (shared fixture ID)") }, modifier = Modifier.semantics { testTag = "consensus.matchId" })
        OutlinedTextField(jersey, { jersey = it.filter(Char::isDigit).take(2) }, label = { Text("Scorer jersey #") }, modifier = Modifier.semantics { testTag = "consensus.jersey" })
        Button(enabled = matchId.isNotEmpty() && jersey.isNotEmpty(), onClick = { run {
            val body = facade.submitGoal(matchId, side, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                System.currentTimeMillis(), jersey.toInt(), UUID.randomUUID().toString())
            client.raw("POST", "/v0/links/${link.id}/consensus/changes", ownerKey, body)
            refresh()
        } }, modifier = Modifier.heightIn(min = 48.dp).semantics { testTag = "consensus.submit" }) { Text("Submit goal for opponent review") }
        Button(enabled = matchId.isNotEmpty(), onClick = { run { refresh() } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh official score") }
        if (snapshot.isNotEmpty()) {
            Text(facade.scoreboard(snapshot), style = MaterialTheme.typography.titleMedium)
            facade.pendingRows(snapshot, side).forEachIndexed { rowIdx, row ->
                val (changeId, label, action) = row.split("|")
                Text(label)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (action == "review") {
                        Button(onClick = { run { client.raw("POST", "/v0/links/${link.id}/consensus/changes/$changeId/accept", ownerKey, facade.reviewBody(UUID.randomUUID().toString())); refresh() } },
                            modifier = Modifier.heightIn(min = 48.dp).semantics { testTag = "consensus.accept.$rowIdx" }) { Text("Accept") }
                        OutlinedButton(onClick = { run { client.raw("POST", "/v0/links/${link.id}/consensus/changes/$changeId/reject", ownerKey, facade.reviewBody(UUID.randomUUID().toString())); refresh() } },
                            modifier = Modifier.heightIn(min = 48.dp).semantics { testTag = "consensus.reject.$rowIdx" }) { Text("Reject") }
                    } else {
                        OutlinedButton(onClick = { run { client.raw("POST", "/v0/links/${link.id}/consensus/changes/$changeId/withdraw", ownerKey, facade.reviewBody(UUID.randomUUID().toString())); refresh() } },
                            modifier = Modifier.heightIn(min = 48.dp).semantics { testTag = "consensus.withdraw.$rowIdx" }) { Text("Withdraw") }
                    }
                }
            }
            Button(onClick = { run {
                client.raw("POST", "/v0/links/${link.id}/consensus/whistle", ownerKey, facade.whistleBody(matchId, side, UUID.randomUUID().toString()))
                refresh()
            } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Final whistle (both sides required)") }
            Text("Record ledger (rejected and withdrawn changes are kept forever)", style = MaterialTheme.typography.labelLarge)
            Text(ledger, style = MaterialTheme.typography.bodySmall)
        }
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
    } }
}
