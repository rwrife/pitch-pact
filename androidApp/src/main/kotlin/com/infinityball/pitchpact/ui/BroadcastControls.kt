package com.infinityball.pitchpact.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.infinityball.pitchpact.BroadcastFacade
import com.infinityball.pitchpact.data.BroadcastTransport
import com.infinityball.pitchpact.domain.MatchDayCodec
import com.infinityball.pitchpact.dto.*
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
    var started by remember(matchId) { mutableStateOf<BroadcastStarted?>(null) }
    var sending by remember { mutableStateOf(false) }
    var seq by remember { mutableLongStateOf(0) }
    var count by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var lastBody by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(started) {
        val session = started ?: return@LaunchedEffect
        while (true) {
            try {
                if (!sending) {
                    sending = true
                    // Pending writes keep exactly the same seq/body on retry after a lost ack.
                    val next = lastBody ?: facade.update(snapshot(), seq + 1, UUID.randomUUID().toString(),
                        SystemClock.elapsedRealtime(), System.currentTimeMillis(), boot).also { lastBody = it }
                    val ack = withContext(Dispatchers.IO) { BroadcastTransport.push(endpoint, session, next) }
                    check(ack.ackSeq == seq + 1) { "Broadcast ack sequence mismatch" }
                    seq = ack.ackSeq
                    lastBody = null
                    val view = withContext(Dispatchers.IO) {
                        val json = BroadcastTransport.watch(endpoint, session.spectatorCode)
                        PitchPactJson.codec.decodeFromString(SpectatorView.serializer(), json)
                    }
                    count = view.spectatorCount
                    message = "Live"
                    sending = false
                }
            } catch (e: Exception) {
                sending = false
                message = "Broadcast queued for retry: ${e.message ?: "network unavailable"}"
            }
            delay(10_000)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = started == null && !sending && capability.isNotBlank() && endpoint.isNotBlank(),
                onClick = {
                    scope.launch {
                        sending = true
                        try {
                            val body = facade.create(matchId, home, away, UUID.randomUUID().toString())
                            val response = withContext(Dispatchers.IO) { BroadcastTransport.start(endpoint, capability, body) }
                            seq = 0; lastBody = null; started = response
                            message = "Broadcast started"
                        } catch (e: Exception) { message = "Start failed: ${e.message ?: "network unavailable"}" }
                        finally { sending = false }
                    }
                }, modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Start broadcast") }
            if (started != null) {
                Button(onClick = {
                    val session = started ?: return@Button
                    scope.launch {
                        sending = true
                        try {
                            withContext(Dispatchers.IO) { BroadcastTransport.stop(endpoint, session) }
                            started = null; lastBody = null; message = "Stopped"
                        } catch (e: Exception) { message = "Stop failed: ${e.message ?: "network unavailable"}" }
                        finally { sending = false }
                    }
                }, enabled = !sending, modifier = Modifier.heightIn(min = 48.dp)) { Text("Stop broadcast") }
            }
        }
        started?.let { session ->
            val link = "${endpoint.trimEnd('/')}/spectate/${session.spectatorCode}"
            Text("Code: ${session.spectatorCode}", style = MaterialTheme.typography.titleMedium)
            Text("Share: $link")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(link)) }) { Text("Copy short link") }
                TextButton(onClick = { clipboard.setText(AnnotatedString(session.spectatorCode)) }) { Text("Copy code") }
            }
            Text("$count watching now")
        }
        if (message.isNotBlank()) Text(message, color = if (message.contains("failed", true)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}
