package com.infinityball.pitchpact.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.infinityball.pitchpact.domain.GuardianContact
import com.infinityball.pitchpact.domain.Kit
import com.infinityball.pitchpact.domain.KitColor
import com.infinityball.pitchpact.domain.Player
import com.infinityball.pitchpact.domain.UniformRequirement
import com.infinityball.pitchpact.domain.ValidationException
import com.infinityball.pitchpact.data.RoomTeamStore

/**
 * Team detail (M2): roster CRUD with jersey validation, per-opponent uniform
 * requirements, and guardian contact management. Every error string is the
 * shared `Validate` wording — the UI adds none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamDetailScreen(store: RoomTeamStore, teamId: String, onBack: () -> Unit) {
    var refresh by remember { mutableStateOf(0) }
    var addPlayer by remember { mutableStateOf(false) }
    var addUniform by remember { mutableStateOf(false) }
    var guardianFor by remember { mutableStateOf<Player?>(null) }
    var deletePlayerTarget by remember { mutableStateOf<Player?>(null) }

    val team = remember(teamId, refresh) { store.team(teamId) } ?: run {
        onBack(); return
    }
    val players = remember(teamId, refresh) { store.players(teamId) }
    val uniforms = remember(teamId, refresh) { store.uniformRequirements(teamId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(team.name) },
                navigationIcon = {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Back to teams" },
                    ) { Text("Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Roster", style = MaterialTheme.typography.titleMedium)
            players.forEach { p ->
                val guardians = remember(p.id, refresh) { store.guardianContacts(p.id) }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Player ${p.name}, jersey ${p.jerseyNumber}" }
                        .padding(vertical = 4.dp),
                ) {
                    Text("#${p.jerseyNumber}  ${p.name}")
                    if (guardians.isNotEmpty()) {
                        Text(
                            guardians.joinToString { g ->
                                buildString {
                                    append(g.guardianName)
                                    if (g.phone.isNotBlank()) append(" · ${g.phone}")
                                    if (g.email.isNotBlank()) append(" · ${g.email}")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { guardianFor = p }) { Text("Guardians") }
                        TextButton(onClick = { deletePlayerTarget = p }) { Text("Remove") }
                    }
                }
                HorizontalDivider()
            }
            if (players.isEmpty()) Text("No players yet.", style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = { addPlayer = true },
                modifier = Modifier.semantics { contentDescription = "Add player" },
            ) { Text("Add player") }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Uniform requirements", style = MaterialTheme.typography.titleMedium)
            uniforms.forEach { u ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Uniform vs ${u.opponentName}" }
                        .padding(vertical = 4.dp),
                ) {
                    Text("vs ${u.opponentName}")
                    Text(
                        buildString {
                            append("Home ${u.homeKit.primary.lowercase()}")
                            u.homeKit.alternate?.let { append("/${it.lowercase()}") }
                            append(" · Away ${u.awayKit.primary.lowercase()}")
                            u.keeperKit?.let { append(" · Keeper ${it.primary.lowercase()}") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (u.equipmentChecklist.isNotEmpty()) {
                        Text("Kit: ${u.equipmentChecklist.joinToString()}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        store.deleteUniformRequirement(u.id); refresh++
                    }) { Text("Remove") }
                }
                HorizontalDivider()
            }
            Button(
                onClick = { addUniform = true },
                modifier = Modifier.semantics { contentDescription = "Add uniform requirement" },
            ) { Text("Add uniform requirement") }
        }
    }

    if (addPlayer) {
        PlayerDialog(
            onDismiss = { addPlayer = false },
            onSave = { name, jersey ->
                try {
                    store.savePlayer(Player(newId(), teamId, name.trim(), jersey, currentTimeMillis()))
                    addPlayer = false; refresh++; null
                } catch (e: ValidationException) {
                    e.message
                }
            },
        )
    }

    if (addUniform) {
        UniformDialog(
            onDismiss = { addUniform = false },
            onSave = { opponent, home, away, kit ->
                try {
                    store.saveUniformRequirement(
                        UniformRequirement(
                            id = newId(), teamId = teamId, opponentName = opponent.trim(),
                            homeKit = Kit(home), awayKit = Kit(away), keeperKit = kit?.let(::Kit),
                            equipmentChecklist = listOf("shinguards"),
                        ),
                    )
                    addUniform = false; refresh++; null
                } catch (e: ValidationException) {
                    e.message
                }
            },
        )
    }

    guardianFor?.let { player ->
        GuardianDialog(
            playerName = player.name,
            onDismiss = { guardianFor = null },
            onRemove = { g -> store.deleteGuardianContact(g.id); refresh++ },
            onAdd = { name, phone, email ->
                try {
                    store.saveGuardianContact(
                        GuardianContact(newId(), player.id, name.trim(), phone.trim(), email.trim()),
                    )
                    refresh++; null
                } catch (e: ValidationException) {
                    e.message
                }
            },
            existing = remember(player.id, refresh) { store.guardianContacts(player.id) },
        )
    }

    deletePlayerTarget?.let { p ->
        AlertDialog(
            onDismissRequest = { deletePlayerTarget = null },
            title = { Text("Remove ${p.name}?") },
            text = { Text("This also removes ${store.guardianContacts(p.id).size} guardian contact(s).") },
            confirmButton = {
                TextButton(onClick = {
                    store.deletePlayer(p.id); deletePlayerTarget = null; refresh++
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { deletePlayerTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PlayerDialog(
    onDismiss: () -> Unit,
    onSave: (String, Int) -> String?,
) {
    var name by remember { mutableStateOf("") }
    var jersey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add player") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Player name") },
                    singleLine = true,
                    modifier = Modifier.semantics { contentDescription = "Player name field" },
                )
                OutlinedTextField(
                    value = jersey,
                    onValueChange = { jersey = it.filter(Char::isDigit).take(2) },
                    label = { Text("Jersey number (1–99)") },
                    singleLine = true,
                    modifier = Modifier.semantics { contentDescription = "Jersey number field" },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    error = onSave(name, jersey.toIntOrNull() ?: -1)
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KitColorDropdown(
    label: String,
    selected: KitColor?,
    allowNone: Boolean,
    onSelect: (KitColor?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = if (allowNone) listOf<KitColor?>(null) + KitColor.entries else KitColor.entries.toList()
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.name?.lowercase() ?: "none",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor()
                .semantics { contentDescription = label },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { color ->
                DropdownMenuItem(
                    text = { Text(color?.name?.lowercase() ?: "none") },
                    onClick = {
                        onSelect(color)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun UniformDialog(
    onDismiss: () -> Unit,
    onSave: (String, KitColor, KitColor, KitColor?) -> String?,
) {
    var opponent by remember { mutableStateOf("") }
    var home by remember { mutableStateOf(KitColor.WHITE) }
    var away by remember { mutableStateOf(KitColor.BLUE) }
    var keeper by remember { mutableStateOf<KitColor?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Uniform requirement") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = opponent, onValueChange = { opponent = it },
                    label = { Text("Opponent") }, singleLine = true,
                    modifier = Modifier.semantics { contentDescription = "Opponent name field" },
                )
                KitColorDropdown("Home kit", home, false) { it?.let { c -> home = c } }
                KitColorDropdown("Away kit", away, false) { it?.let { c -> away = c } }
                KitColorDropdown("Keeper kit (optional)", keeper, true) { keeper = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = { error = onSave(opponent, home, away, keeper) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun GuardianDialog(
    playerName: String,
    existing: List<GuardianContact>,
    onDismiss: () -> Unit,
    onRemove: (GuardianContact) -> Unit,
    onAdd: (String, String, String) -> String?,
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Guardians — $playerName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                existing.forEach { g ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "${g.guardianName} ${g.phone} ${g.email}".trim(),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onRemove(g) }) { Text("Remove") }
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Guardian name") }, singleLine = true)
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone") }, singleLine = true)
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true)
                Text(
                    "Guardian contacts stay on this device — never shared or broadcast.",
                    style = MaterialTheme.typography.bodySmall,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = { error = onAdd(name, phone, email) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
