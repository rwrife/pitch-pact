package com.infinityball.pitchpact.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.infinityball.pitchpact.data.RoomBackupStore
import com.infinityball.pitchpact.domain.BackupCounts
import com.infinityball.pitchpact.domain.BackupCodec
import com.infinityball.pitchpact.CsvExport

/** Private backup stays in user-selected document; sharing CSV never includes roster/contact details. */
@Composable
fun BackupControls(backup: RoomBackupStore, onRestored: () -> Unit) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<String?>(null) }
    var counts by remember { mutableStateOf<BackupCounts?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(backup.export().toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("Could not write selected document")
        } catch (e: Exception) { error = e.message ?: "Backup failed" }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val data = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw IllegalStateException("Could not read selected document")
            counts = backup.preview(data) // Validate before enabling destructive replacement.
            pending = data
        } catch (e: Exception) { error = e.message ?: "Invalid backup" }
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row {
        TextButton(onClick = { save.launch("pitch-pact-backup.json") }) { Text("Backup JSON") }
        TextButton(onClick = { open.launch(arrayOf("application/json", "text/plain")) }) { Text("Restore JSON") }
        TextButton(onClick = {
            try {
                val csv = CsvExport.render("schedule", BackupCodec.decode(backup.export()))
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"; putExtra(Intent.EXTRA_TEXT, csv)
                }, "Share schedule CSV"))
            } catch (e: Exception) { error = e.message ?: "Export failed" }
        }) { Text("Schedule CSV") }
    }
    Row {
        TextButton(onClick = {
            try {
                val csv = CsvExport.render("results", BackupCodec.decode(backup.export()))
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"; putExtra(Intent.EXTRA_TEXT, csv)
                }, "Share results CSV"))
            } catch (e: Exception) { error = e.message ?: "Export failed" }
        }) { Text("Results CSV") }
        TextButton(onClick = {
            try {
                val csv = CsvExport.render("availability", BackupCodec.decode(backup.export()))
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"; putExtra(Intent.EXTRA_TEXT, csv)
                }, "Share availability CSV"))
            } catch (e: Exception) { error = e.message ?: "Export failed" }
        }) { Text("Availability CSV") }
    }
    }
    counts?.let { preview ->
        AlertDialog(
            onDismissRequest = { counts = null; pending = null },
            title = { Text("Replace local data?") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Incoming: ${preview.teams} teams, ${preview.players} players, ${preview.guardianContacts} guardian contacts, ${preview.uniforms} uniforms, ${preview.locations} locations, ${preview.tournaments} tournaments, ${preview.fixtures} fixtures, ${preview.availability} responses, ${preview.matches} matches, ${preview.ledgerEvents} events, ${preview.consensusDecisions} decisions.")
                Text("All current local records will be replaced. Cancel changes nothing.")
            } },
            confirmButton = { Button(onClick = {
                try {
                    pending?.let { backup.replace(it); onRestored() }
                } catch (e: Exception) { error = e.message ?: "Restore failed" }
                pending = null; counts = null
            }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { pending = null; counts = null }) { Text("Cancel") } },
        )
    }
    error?.let { message ->
        AlertDialog(onDismissRequest = { error = null }, title = { Text("Data operation failed") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } })
    }
}
