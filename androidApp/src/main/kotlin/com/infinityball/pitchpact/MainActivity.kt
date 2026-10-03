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
        val store = RoomTeamStore(PitchPactDatabase.build(applicationContext))
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Navigation(store)
                }
            }
        }
    }
}

@Composable
private fun Navigation(store: RoomTeamStore) {
    var selectedTeamId by remember { mutableStateOf<String?>(null) }
    val team = selectedTeamId?.let { id -> store.team(id) }
    if (team == null) {
        TeamDirectoryScreen(
            store = store,
            onOpenTeam = { selectedTeamId = it },
        )
    } else {
        TeamDetailScreen(
            store = store,
            teamId = team.id,
            onBack = { selectedTeamId = null },
        )
    }
}
