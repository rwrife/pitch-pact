package com.infinityball.pitchpact.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.infinityball.pitchpact.data.RoomScheduleStore
import com.infinityball.pitchpact.data.RoomTeamStore
import com.infinityball.pitchpact.domain.*
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.UUID

/** Native Android scheduling surface; every conflict/round/rollup decision comes from shared. */
@Composable fun ScheduleScreen(teamStore: RoomTeamStore, schedule: RoomScheduleStore, onBack: () -> Unit) {
    val context = LocalContext.current
    var reminder by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) reminder = false
    }
    var revision by remember { mutableIntStateOf(0) }
    val teams = remember(revision) { teamStore.teams() }
    val games = remember(revision) { schedule.fixtures() }
    val locations = remember(revision) { schedule.locations() }
    val tournaments = remember(revision) { schedule.tournaments() }
    var title by remember { mutableStateOf("") }
    var locationName by remember { mutableStateOf("") }
    var tournamentName by remember { mutableStateOf("") }
    var selectedHome by remember { mutableStateOf<String?>(null) }
    var selectedAway by remember { mutableStateOf<String?>(null) }
    var selectedTournament by remember { mutableStateOf<String?>(null) }
    var format by remember { mutableStateOf(TournamentFormat.ROUND_ROBIN) }
    var start by remember { mutableLongStateOf(System.currentTimeMillis() + 86_400_000L) }
    var warning by remember { mutableStateOf("") }
    var selectedGame by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack) { Text("← Teams") }
        Text("Upcoming games", style = MaterialTheme.typography.headlineMedium)
        games.filter { it.startEpochMillis >= System.currentTimeMillis() }.forEach { game ->
            TextButton(onClick = { selectedGame = game.id }) {
                Text("${game.title} · ${DateFormat.getDateTimeInstance().format(Date(game.startEpochMillis))}" +
                    if (game.resultStatus == ResultStatus.PENDING) " · result pending" else "")
            }
        }
        if (selectedGame != null) {
            val game = games.firstOrNull { it.id == selectedGame }
            if (game != null) {
                Text("Availability: ${game.title}", style = MaterialTheme.typography.titleMedium)
                val roster = teamStore.players(game.homeTeamId) + teamStore.players(game.awayTeamId)
                val responses = remember(revision, selectedGame) { schedule.availability(game.id) }
                val rollup = Scheduling.rollup(game.id, roster.map { it.id }, responses)
                Text("Yes ${rollup.yes} · No ${rollup.no} · Maybe ${rollup.maybe} · Unanswered ${rollup.unanswered}")
                roster.forEach { player ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("#${player.jerseyNumber} ${player.name}", Modifier.weight(1f))
                        Availability.entries.forEach { choice ->
                            TextButton(onClick = {
                                schedule.saveAvailability(AvailabilityResponse(game.id, player.id, choice)); revision++
                            }) { Text(if (responses.any { it.playerId == player.id && it.choice == choice }) "✓${choice.name}" else choice.name) }
                        }
                    }
                }
                TextButton(onClick = { schedule.deleteFixture(game.id); selectedGame = null; revision++ }) { Text("Delete fixture") }
            }
        }
        HorizontalDivider()
        Text("Locations", style = MaterialTheme.typography.titleMedium)
        locations.forEach { place -> Text("${place.name}: ${place.address}") }
        OutlinedTextField(value = locationName, onValueChange = { locationName = it }, label = { Text("Field name / address") })
        Button(onClick = { if (locationName.isNotBlank()) { schedule.saveLocation(Location(UUID.randomUUID().toString(), locationName)); locationName = ""; revision++ } }) { Text("Add location") }
        Text("Tournaments", style = MaterialTheme.typography.titleMedium)
        tournaments.forEach { tournament ->
            Text("${tournament.name} (${tournament.format.name})")
            val draft = Scheduling.draft(tournament.teamIds, tournament.format)
            draft.forEach { pairing -> Text("Round ${pairing.round}: ${pairing.homeTeamId ?: "TBD"} vs ${pairing.awayTeamId ?: "BYE/TBD"}") }
            val standings = Scheduling.standings(tournament.teamIds, games.filter { it.tournamentId == tournament.id })
            standings.forEach { Text("${it.teamId}: ${it.points} pts (${it.played} official played)") }
            TextButton(onClick = { schedule.deleteTournament(tournament.id); revision++ }) { Text("Delete tournament") }
        }
        OutlinedTextField(value = tournamentName, onValueChange = { tournamentName = it }, label = { Text("Tournament name") })
        Row { TournamentFormat.entries.forEach { f -> FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.name) }) } }
        Button(enabled = tournamentName.isNotBlank() && teams.size >= 2, onClick = {
            schedule.saveTournament(Tournament(UUID.randomUUID().toString(), tournamentName, teams.map { it.id }, format))
            tournamentName = ""; revision++
        }) { Text("Create tournament (all active teams)") }
        HorizontalDivider()
        Text("New fixture (tournament or ad-hoc)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Game title") })
        Text("Home")
        teams.forEach { FilterChip(selected = selectedHome == it.id, onClick = { selectedHome = it.id }, label = { Text(it.name) }) }
        Text("Away")
        teams.forEach { FilterChip(selected = selectedAway == it.id, onClick = { selectedAway = it.id }, label = { Text(it.name) }) }
        Text("Location")
        locations.forEach { FilterChip(selected = locationName == it.name, onClick = { locationName = it.name }, label = { Text(it.name) }) }
        Text("Tournament (optional)")
        FilterChip(selected = selectedTournament == null, onClick = { selectedTournament = null }, label = { Text("Ad-hoc league") })
        tournaments.forEach { FilterChip(selected = selectedTournament == it.id, onClick = { selectedTournament = it.id }, label = { Text(it.name) }) }
        Button(onClick = { pickDateTime(context, start) { start = it } }) { Text("Kickoff: ${DateFormat.getDateTimeInstance().format(Date(start))}") }
        Row { Checkbox(checked = reminder, onCheckedChange = {
            reminder = it
            if (it && Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }); Text("Remind me one hour before (local)") }
        Button(enabled = title.isNotBlank() && selectedHome != null && selectedAway != null, onClick = {
            val game = Fixture(UUID.randomUUID().toString(), title, selectedHome!!, selectedAway!!,
                start, 90, locations.firstOrNull { it.name == locationName }?.id, selectedTournament,
                reminderMinutesBefore = if (reminder) 60 else null)
            try {
                val clashes = schedule.saveFixture(game)
                if (reminder) LocalGameReminders.schedule(context, game)
                warning = if (clashes.isEmpty()) "Saved" else clashes.joinToString("; ") { it.reason }
                title = ""; revision++
            } catch (failure: Exception) { warning = failure.message ?: "Could not save" }
        }) { Text("Save fixture") }
        if (warning.isNotEmpty()) Text(warning, color = MaterialTheme.colorScheme.error)
    }
}

private fun pickDateTime(context: Context, current: Long, onPicked: (Long) -> Unit) {
    val calendar = Calendar.getInstance().apply { timeInMillis = current }
    DatePickerDialog(context, { _, y, m, d ->
        TimePickerDialog(context, { _, h, minute ->
            calendar.set(y, m, d, h, minute)
            calendar.set(Calendar.SECOND, 0)
            onPicked(calendar.timeInMillis)
        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
    }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
}
