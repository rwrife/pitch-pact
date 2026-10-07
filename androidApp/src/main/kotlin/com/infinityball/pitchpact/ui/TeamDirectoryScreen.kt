package com.infinityball.pitchpact.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.platform.LocalContext
import com.infinityball.pitchpact.data.RoomScheduleStore
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.infinityball.pitchpact.domain.Team
import com.infinityball.pitchpact.domain.ValidationException
import com.infinityball.pitchpact.data.RoomTeamStore

/**
 * Team directory (M2): list, create, archive toggle, deletion with explicit
 * cascade preview. Error wording comes verbatim from shared validation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamDirectoryScreen(store: RoomTeamStore, schedule: RoomScheduleStore, onOpenTeam: (String) -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    var showArchived by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Team?>(null) }

    val teams = remember(refresh, showArchived) { store.teams(includeArchived = showArchived) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Teams",
                        modifier = Modifier.semantics { contentDescription = "Team directory" },
                    )
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreate = true },
                modifier = Modifier.semantics { contentDescription = "Create team" },
            ) { Text("+") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Checkbox(
                    checked = showArchived,
                    onCheckedChange = { showArchived = it },
                    modifier = Modifier.semantics { contentDescription = "Show archived teams" },
                )
                Text("Show archived")
            }
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize()) {
                items(teams, key = { it.id }) { team ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenTeam(team.id) }
                            .semantics { contentDescription = "Team ${team.name}" }
                            .padding(16.dp),
                    ) {
                        Text(team.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            buildString {
                                append("${store.players(team.id).size} players")
                                if (team.isArchived) append(" · archived")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    store.archiveTeam(team.id, !team.isArchived, currentTimeMillis())
                                    refresh++
                                },
                            ) { Text(if (team.isArchived) "Unarchive" else "Archive") }
                            TextButton(onClick = { deleteTarget = team }) { Text("Delete") }
                        }
                    }
                    HorizontalDivider()
                }
                if (teams.isEmpty()) {
                    item {
                        Text(
                            "No teams yet. Tap + to create one.",
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        NameDialog(
            title = "New team",
            label = "Team name",
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                try {
                    store.saveTeam(Team(newId(), name, currentTimeMillis()))
                    showCreate = false
                    refresh++
                    null // success: no error to show
                } catch (e: ValidationException) {
                    e.message // shared validation wording surfaces in the dialog
                }
            },
        )
    }

    deleteTarget?.let { team ->
        val preview = remember(team.id, refresh) { store.deletionPreview(team.id) }
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${team.name}?") },
            text = {
                Text(
                    if (preview.blockingCount == 0) {
                        "This team has no players, uniforms, or guardian contacts. Deleting removes it permanently."
                    } else {
                        "This deletes the team AND ${preview.playerCount} players, " +
                            "${preview.uniformRequirementCount} uniform requirements, " +
                            "${preview.guardianContactCount} guardian contacts. This cannot be undone."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        schedule.fixtures().filter { it.homeTeamId == team.id || it.awayTeamId == team.id }
                            .forEach { LocalGameReminders.cancel(context, it.id) }
                        store.deleteTeam(team.id, requireEmpty = false)
                        deleteTarget = null
                        refresh++
                    },
                ) { Text("Delete everything") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Simple labelled name-entry dialog used by create flows. `onConfirm` returns
 * null on success (dialog closes) or an error message (shared validation
 * wording) which is rendered inside the dialog.
 */
@Composable
fun NameDialog(
    title: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> String?,
) {
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.semantics { contentDescription = label },
                )
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { error = onConfirm(value) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun currentTimeMillis(): Long = System.currentTimeMillis()
fun newId(): String = java.util.UUID.randomUUID().toString()
