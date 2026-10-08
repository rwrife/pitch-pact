package com.infinityball.pitchpact.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.infinityball.pitchpact.MatchDayFacade
import com.infinityball.pitchpact.data.*
import com.infinityball.pitchpact.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.UUID

@Composable fun MatchDayScreen(id: String, store: RoomMatchDayStore, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var endpoint by remember { mutableStateOf("") }
    var capability by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }
    var recoveredElapsed by remember { mutableStateOf("") }
    var correctionTarget by remember { mutableStateOf("") }
    val rules = remember { MatchDayFacade() }
    var json by remember(id) { mutableStateOf(MatchDayCodec.encode(store.load(id))) }
    var tick by remember { mutableLongStateOf(0) }
    var side by remember { mutableStateOf("HOME") }
    var kind by remember { mutableStateOf("OPEN_PLAY") }
    var jersey by remember { mutableStateOf("9") }
    var assist by remember { mutableStateOf("") }
    var other by remember { mutableStateOf("10") }
    var error by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val boot = remember { runCatching {
        android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT).toString()
    }.getOrElse { "uncorrelated-${UUID.randomUUID()}" } }
    LaunchedEffect(Unit) { while (true) { tick = SystemClock.elapsedRealtime(); delay(1000) } }
    fun change(block: () -> String) { try { val next = block(); store.save(MatchDayCodec.decode(next)); json = next; error = "" } catch (e: Exception) { error = e.message ?: "Capture failed" } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back to fixture") }
        Text("OFFICIAL ${rules.official(json)} (opposing acceptance available in M7)")
        Text("Pending score ${rules.pending(json)}")
        Text(runCatching { rules.clock(json, tick, System.currentTimeMillis(), boot) }.getOrDefault("Clock requires recovery"))
        Button(onClick = { change { rules.advance(json, SystemClock.elapsedRealtime(), System.currentTimeMillis(), boot, false, UUID.randomUUID().toString(), side) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Advance clock") }
        Button(onClick = { change { rules.advance(json, SystemClock.elapsedRealtime(), System.currentTimeMillis(), boot, true, UUID.randomUUID().toString(), side) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Start stoppage") }
        OutlinedTextField(recoveredElapsed, { recoveredElapsed = it }, label = { Text("Confirmed elapsed seconds for clock recovery") })
        Button(onClick = { change { rules.recoverClock(json, SystemClock.elapsedRealtime(), System.currentTimeMillis(), boot, recoveredElapsed.toLong() * 1000) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Confirm clock recovery") }
        Row { listOf("HOME", "AWAY").forEach { value -> TextButton(onClick = { side = value }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (side == value) "✓ $value" else value) } } }
        var choosingKind by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { choosingKind = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Event: ${kind.lowercase().replace('_', ' ')}") }
            DropdownMenu(expanded = choosingKind, onDismissRequest = { choosingKind = false }) {
                (GoalType.entries.map { it.name } + listOf("PENALTY_MISS", "SUBSTITUTION", "YELLOW", "RED") + ExtraKind.entries.map { it.name }).forEach { value ->
                    DropdownMenuItem(text = { Text(value.lowercase().replace('_', ' ')) }, onClick = { kind = value; choosingKind = false }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        OutlinedTextField(jersey, { jersey = it }, label = { Text("Jersey") })
        OutlinedTextField(assist, { assist = it }, label = { Text("Optional assist") })
        OutlinedTextField(other, { other = it }, label = { Text("Substitute on jersey") })
        OutlinedTextField(correctionTarget, { correctionTarget = it }, label = { Text("Correction target event ID (optional)") })
        Button(onClick = { change {
            val captured = rules.captureInput(if (correctionTarget.isBlank()) json else rules.initial(id), UUID.randomUUID().toString(), side, System.currentTimeMillis(), kind, jersey, assist, other)
            if (correctionTarget.isBlank()) captured else rules.correct(json, correctionTarget, captured, UUID.randomUUID().toString(), System.currentTimeMillis())
        } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Capture event") }
        Button(onClick = { change { rules.undo(json) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Undo uncommitted tail") }
        ExtraKind.entries.forEach { extra ->
            val enabled = extra in MatchDayCodec.decode(json).enabledExtras
            Row { Checkbox(enabled, { selected -> change {
                val extras = MatchDayCodec.decode(json).enabledExtras.toMutableSet()
                if (selected) extras.add(extra) else extras.remove(extra)
                rules.extras(json, extras.joinToString(",") { it.name })
            } }); Text("Track ${extra.name}") }
        }
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Server endpoint (HTTPS)") })
        OutlinedTextField(capability, { capability = it }, label = { Text("Match capability") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        Button(enabled = !syncing, onClick = {
            syncing = true
            val snapshot = try {
                val prepared = MatchDayCodec.decode(rules.prepareSync(json))
                store.save(prepared); json = MatchDayCodec.encode(prepared); prepared
            } catch (e: Exception) {
                error = e.message ?: "Could not persist sync attempt"; syncing = false; return@Button
            }
            scope.launch {
                try {
                    val receipt = withContext(Dispatchers.IO) { MatchDaySync.send(endpoint, capability, snapshot) }
                    change { MatchDayCodec.encode(MatchDayCodec.decode(json).acknowledge(receipt.events)) }
                } catch (e: Exception) { error = e.message ?: "Sync failed; queue retained" }
                finally { syncing = false }
            }
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Sync queued events") }
        Text(error)
        Text(rules.audit(json))
    }
}
