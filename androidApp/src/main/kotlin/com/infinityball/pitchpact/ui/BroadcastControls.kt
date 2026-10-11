package com.infinityball.pitchpact.ui

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.infinityball.pitchpact.BroadcastFacade
import com.infinityball.pitchpact.data.*
import com.infinityball.pitchpact.dto.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable fun BroadcastControls(
    matchId: String,
    home: String,
    away: String,
    endpoint: String,
    capability: String,
    boot: String,
    snapshot: () -> String,
) {
    val facade = remember { BroadcastFacade() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val store = remember(context) { BroadcastCheckpointStore(context) }
    val currentSnapshot by rememberUpdatedState(snapshot)
    var checkpoint by remember(matchId) { mutableStateOf<BroadcastCheckpoint?>(null) }
    var recovered by remember(matchId) { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    fun persist(value: BroadcastCheckpoint?) {
        store.save(matchId, value)
        checkpoint = value
    }

    LaunchedEffect(matchId) {
        try {
            checkpoint = store.load(matchId)
            recovered = true
        } catch (e: Exception) {
            message = "Broadcast recovery failed; saved credentials were not replaced."
        }
    }
    LaunchedEffect(checkpoint?.started?.spectatorCode) {
        if (checkpoint == null) return@LaunchedEffect
        while (true) {
            if (!sending) {
                sending = true
                try {
                    var current = checkpoint ?: return@LaunchedEffect
                    if (current.pendingBody == null) {
                        current = current.copy(pendingBody = facade.update(currentSnapshot(), current.seq + 1,
                            UUID.randomUUID().toString(), SystemClock.elapsedRealtime(), System.currentTimeMillis(), boot))
                        persist(current) // Durable before network I/O; replay identical bytes after lost ack/restart.
                    }
                    val ack = withContext(Dispatchers.IO) { BroadcastTransport.push(current.endpoint, current.started, current.pendingBody!!) }
                    check(ack.ackSeq == current.seq + 1) { "Broadcast ack sequence mismatch" }
                    persist(current.copy(seq = ack.ackSeq, pendingBody = null))
                    val view = withContext(Dispatchers.IO) {
                        val json = BroadcastTransport.watch(current.endpoint, current.started.spectatorCode)
                        PitchPactJson.codec.decodeFromString(SpectatorView.serializer(), json)
                    }
                    count = view.spectatorCount
                    message = "Live"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = "Broadcast queued for retry: ${e.message ?: "network unavailable"}" }
                finally { sending = false }
            }
            delay(10_000)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = recovered && checkpoint == null && !sending && capability.isNotBlank() && endpoint.isNotBlank(),
                onClick = {
                    scope.launch {
                        sending = true
                        try {
                            val body = facade.create(matchId, home, away, UUID.randomUUID().toString())
                            val response = withContext(Dispatchers.IO) { BroadcastTransport.start(endpoint, capability, body) }
                            persist(BroadcastCheckpoint(endpoint, response))
                            message = "Broadcast started"
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { message = "Start failed: ${e.message ?: "network unavailable"}" }
                        finally { sending = false }
                    }
                }, modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Start broadcast") }
            if (checkpoint != null) {
                Button(onClick = {
                    val current = checkpoint ?: return@Button
                    scope.launch {
                        sending = true
                        try {
                            withContext(Dispatchers.IO) { BroadcastTransport.stop(current.endpoint, current.started) }
                            persist(null)
                            message = "Stopped"
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { message = "Stop failed: ${e.message ?: "network unavailable"}; session retained" }
                        finally { sending = false }
                    }
                }, enabled = !sending, modifier = Modifier.heightIn(min = 48.dp)) { Text("Stop broadcast") }
            }
        }
        checkpoint?.let { current ->
            val link = "${current.endpoint.trimEnd('/')}/spectate/${current.started.spectatorCode}"
            val qr = remember(link) { spectatorQr(link) }
            Text("Code: ${current.started.spectatorCode}", style = MaterialTheme.typography.titleMedium)
            Image(qr.asImageBitmap(), "QR code for spectator link", Modifier.size(180.dp))
            Text("Share: $link")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(link)) }) { Text("Copy short link") }
                TextButton(onClick = { clipboard.setText(AnnotatedString(current.started.spectatorCode)) }) { Text("Copy code") }
            }
            Text("$count recent viewers (approximate)")
        }
        Text("Keep this screen open to publish. Offline requests and the session survive app restart.", style = MaterialTheme.typography.bodySmall)
        if (message.isNotBlank()) Text(message, color = if (message.contains("failed", true)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}
