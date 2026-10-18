package com.infinityball.pitchpact.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.infinityball.pitchpact.data.ConsensusOutboxStore
import com.infinityball.pitchpact.dto.PitchPactJson
import com.infinityball.pitchpact.domain.ConsensusOutbox
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
    val context = LocalContext.current
    val outboxStore = remember(context) { ConsensusOutboxStore(context) }
    val side = remember(link.id, myTeamId) { facade.sideFor(link.firstTeamId, link.secondTeamId, myTeamId) }
    var matchId by remember { mutableStateOf("") }
    var jersey by remember { mutableStateOf("") }
    var snapshot by remember { mutableStateOf("") }
    var ledger by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var outbox by remember(client.base, link.id, myTeamId) { mutableStateOf("") }
    fun loadOutbox() {
        if (outbox.isEmpty()) outbox = outboxStore.load(client.base, link.id, myTeamId) ?: facade.outboxNew()
    }
    fun persist(value: String) {
        outboxStore.save(client.base, link.id, myTeamId, value)
        outbox = value
    }
    LaunchedEffect(client.base, link.id, myTeamId) {
        try { outbox = outboxStore.load(client.base, link.id, myTeamId) ?: facade.outboxNew() }
        catch (e: Exception) { message = "Outbox unreadable; recover the device file before scoring" }
    }
    suspend fun flush() {
        loadOutbox()
        while (outbox.isNotEmpty()) {
            val entry = facade.outboxHead(outbox)
            if (entry.isEmpty()) return
            val status = client.statusOf("POST", facade.outboxPath(entry), ownerKey, facade.outboxBody(entry))
            val next = when (status) {
                in 200..299 -> facade.outboxDelivered(outbox, facade.outboxId(entry))
                in 400..499 -> facade.outboxRefuse(outbox, facade.outboxId(entry), status)
                else -> return // offline or server failure: exact body/key retained for retry
            }
            persist(next)
        }
    }
    suspend fun send(path: String, body: String, id: String) {
        loadOutbox()
        persist(facade.outboxEnqueue(outbox, id, path, body)) // commit BEFORE first network attempt
        flush()
    }
    fun run(action: suspend () -> Unit) { scope.launch { try { action(); message = "" } catch (e: Exception) { message = e.message ?: "Consensus failed" } } }
    suspend fun refresh() {
        flush()
        require(matchId.matches(Regex("[A-Za-z0-9:_-]{1,128}"))) { "Match ID must be 1-128 letters, digits, colon, underscore, or hyphen" }
        snapshot = client.raw("GET", "/v0/links/${link.id}/consensus?matchId=$matchId", ownerKey, null)
        ledger = facade.recordLedger(snapshot)
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Column(Modifier.padding(8.dp)) {
        Text("Consensus scoring · you are the ${side.lowercase()} sideline", style = MaterialTheme.typography.titleSmall)
        if (outbox.isNotEmpty()) Text("Offline outbox: ${facade.outboxSummary(outbox)}", modifier = Modifier.semantics { testTag = "consensus.outbox" })
        OutlinedTextField(matchId, { matchId = it.trim() }, label = { Text("Match ID (shared fixture ID)") }, modifier = Modifier.semantics { testTag = "consensus.matchId" })
        OutlinedTextField(jersey, { jersey = it.filter(Char::isDigit).take(2) }, label = { Text("Scorer jersey #") }, modifier = Modifier.semantics { testTag = "consensus.jersey" })
        Button(enabled = matchId.isNotEmpty() && jersey.isNotEmpty(), onClick = { run {
            val body = facade.submitGoal(matchId, side, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                System.currentTimeMillis(), jersey.toInt(), UUID.randomUUID().toString())
            send("/v0/links/${link.id}/consensus/changes", body, UUID.randomUUID().toString())
            refresh()
        } }, modifier = Modifier.heightIn(min = 48.dp).semantics { testTag = "consensus.submit" }) { Text("Submit goal for opponent review") }
        Button(enabled = matchId.isNotEmpty(), onClick = { run { refresh() } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh official score") }
        if (snapshot.isNotEmpty()) {
            Text(facade.scoreboard(snapshot), style = MaterialTheme.typography.titleMedium)
            // Own accepted goals may be removed or corrected — always as a NEW pending decision.
            facade.acceptedGoalRows(snapshot, side).forEach { row ->
                val (targetId, label) = row.split("|")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$label (your sideline)")
                    Button(onClick = { run {
                        val body = facade.changeGoal(snapshot, side, targetId, "remove",
                            UUID.randomUUID().toString(), targetId, targetId,
                            System.currentTimeMillis(), 0, UUID.randomUUID().toString())
                        send("/v0/links/${link.id}/consensus/changes", body, UUID.randomUUID().toString())
                        refresh()
                    } }, modifier = Modifier.heightIn(min = 48.dp).semantics { testTag = "consensus.remove.$targetId" }) { Text("Remove") }
                    Button(enabled = jersey.isNotEmpty(), onClick = { run {
                        val replacementId = UUID.randomUUID().toString()
                        val body = facade.changeGoal(snapshot, side, targetId, "correct",
                            UUID.randomUUID().toString(), UUID.randomUUID().toString(), replacementId,
                            System.currentTimeMillis(), jersey.toInt(), UUID.randomUUID().toString())
                        send("/v0/links/${link.id}/consensus/changes", body, UUID.randomUUID().toString())
                        refresh()
                    } }, modifier = Modifier.heightIn(min = 48.dp).semantics { testTag = "consensus.correct.$targetId" }) { Text("Correct to #") }
                }
            }
            facade.pendingRows(snapshot, side).forEachIndexed { rowIdx, row ->
                val (changeId, label, action) = row.split("|")
                Text(label)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (action == "review") {
                        Button(onClick = { run { send("/v0/links/${link.id}/consensus/changes/$changeId/accept", facade.reviewBody(UUID.randomUUID().toString()), UUID.randomUUID().toString()); refresh() } },
                            modifier = Modifier.heightIn(min = 48.dp).semantics { testTag = "consensus.accept.$rowIdx" }) { Text("Accept") }
                        OutlinedButton(onClick = { run { send("/v0/links/${link.id}/consensus/changes/$changeId/reject", facade.reviewBody(UUID.randomUUID().toString()), UUID.randomUUID().toString()); refresh() } },
                            modifier = Modifier.heightIn(min = 48.dp).semantics { testTag = "consensus.reject.$rowIdx" }) { Text("Reject") }
                    } else {
                        OutlinedButton(onClick = { run { send("/v0/links/${link.id}/consensus/changes/$changeId/withdraw", facade.reviewBody(UUID.randomUUID().toString()), UUID.randomUUID().toString()); refresh() } },
                            modifier = Modifier.heightIn(min = 48.dp).semantics { testTag = "consensus.withdraw.$rowIdx" }) { Text("Withdraw") }
                    }
                }
            }
            Button(onClick = { run {
                send("/v0/links/${link.id}/consensus/whistle", facade.whistleBody(matchId, side, UUID.randomUUID().toString()), UUID.randomUUID().toString())
                refresh()
            } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Final whistle (both sides required)") }
            Text("Record ledger (rejected and withdrawn changes are kept forever)", style = MaterialTheme.typography.labelLarge)
            Text(ledger, style = MaterialTheme.typography.bodySmall)
        }
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
    } }
}
