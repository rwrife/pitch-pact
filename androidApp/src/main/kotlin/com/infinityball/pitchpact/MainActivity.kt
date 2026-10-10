package com.infinityball.pitchpact

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.infinityball.pitchpact.data.PitchPactDatabase
import com.infinityball.pitchpact.data.RoomTeamStore
import com.infinityball.pitchpact.data.RoomScheduleStore
import com.infinityball.pitchpact.ui.ScheduleScreen
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import com.infinityball.pitchpact.ui.TeamDirectoryScreen
import com.infinityball.pitchpact.ui.TeamDetailScreen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize

/**
 * M2 Android shell: hosts the Compose CRUD UX over the Room store. All rules
 * live in `shared` — the UI only renders store results and forwards edits.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = PitchPactDatabase.build(applicationContext)
        val store = RoomTeamStore(db)
        val schedule = RoomScheduleStore(db)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Navigation(store, schedule)
                }
            }
        }
    }
}

@Composable
private fun Navigation(store: RoomTeamStore, schedule: RoomScheduleStore) {
    var showSchedule by remember { mutableStateOf(false) }
    var showSpectator by remember { mutableStateOf(false) }
    var selectedTeamId by remember { mutableStateOf<String?>(null) }
    if (showSpectator) {
        com.infinityball.pitchpact.ui.SpectatorScreen(onBack = { showSpectator = false })
        return
    }
    if (showSchedule) {
        ScheduleScreen(store, schedule, onBack = { showSchedule = false })
        return
    }
    val team = selectedTeamId?.let { id -> store.team(id) }
    if (team == null) {
        Column {
            Row {
                TextButton(onClick = { showSchedule = true }) { Text("Games & tournaments") }
                TextButton(onClick = { showSpectator = true }) { Text("Watch live game") }
            }
            TeamDirectoryScreen(
                store = store,
                schedule = schedule,
                onOpenTeam = { selectedTeamId = it },
            )
        }
    } else {
        TeamDetailScreen(
            store = store,
            teamId = team.id,
            onBack = { selectedTeamId = null },
        )
    }
}
