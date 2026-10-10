package com.infinityball.pitchpact.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.infinityball.pitchpact.BroadcastFacade
import com.infinityball.pitchpact.data.BroadcastTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Read-only spectator screen for friends, family, and distant teammates. */
@Composable fun SpectatorScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val facade = remember { BroadcastFacade() }
    var endpoint by remember { mutableStateOf("https://pitchpact.infinityball.com") }
    var code by remember { mutableStateOf("") }
    var watching by remember { mutableStateOf(false) }
    var json by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(watching, code, endpoint) {
        if (!watching || code.length != 6) return@LaunchedEffect
        while (watching) {
            try {
                val next = withContext(Dispatchers.IO) { BroadcastTransport.watch(endpoint, code.uppercase()) }
                json = next
                error = ""
            } catch (e: Exception) {
                error = e.message ?: "Watch failed"
            }
            delay(10_000)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("← Back") }
        Text("Watch live match", style = MaterialTheme.typography.headlineMedium)
        Text("Anonymous read-only feed. No account or sign-in required.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Server endpoint (HTTPS)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.uppercase().take(6) },
            label = { Text("6-character broadcast code") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !watching && code.length == 6,
                onClick = {
                    watching = true
                    error = ""
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Watch") }
            if (watching) {
                Button(
                    onClick = { watching = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Stop watching") }
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (json.isNotBlank()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(facade.summary(json), style = MaterialTheme.typography.titleMedium)
            Text("Play by play:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            Text(facade.events(json), style = MaterialTheme.typography.bodySmall)
        }
    }
}
