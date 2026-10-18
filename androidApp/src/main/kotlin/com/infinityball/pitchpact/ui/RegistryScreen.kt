package com.infinityball.pitchpact.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.infinityball.pitchpact.CoordinationFacade
import com.infinityball.pitchpact.data.RoomTeamStore
import com.infinityball.pitchpact.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * M6 registry client — transport only. Every rule (opt-in field set, code death on
 * rotate, confirm-before-show) lives in `shared` + `server`; this class just moves
 * shared DTOs over HTTPS to the user's self-hosted instance.
 */
class RegistryStatus(val code: Int) : Exception("Registry HTTP $code")

class RegistryClient(val base: String) {
    private val json = PitchPactJson.codec
    private fun checked(): String {
        val b = base.trimEnd('/')
        require(b.startsWith("https://") || b.startsWith("http://127.0.0.1:") || b.startsWith("http://localhost:") ||
            b.startsWith("http://10.0.2.2:")) { "Registry URL must be HTTPS (loopback HTTP allowed for dev)" }
        return b
    }
    private fun call(method: String, path: String, key: String?, body: String?): String =
        URL(checked() + path).openConnection().let { raw ->
            val conn = raw as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.instanceFollowRedirects = false // never forward owner capability to another host
                conn.connectTimeout = 10_000; conn.readTimeout = 10_000
                if (key != null) conn.setRequestProperty("Authorization", "Bearer $key")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                if (code !in 200..299) throw RegistryStatus(code)
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally { conn.disconnect() }
        }
    /** Raw JSON transport for the consensus panel; DTO parsing and wording stay in shared ConsensusFacade. */
    suspend fun raw(method: String, path: String, key: String, body: String?): String =
        withContext(Dispatchers.IO) { call(method, path, key, body) }
    /** HTTP status only for outbox policy; a failed connection or unavailable transport is zero. */
    suspend fun statusOf(method: String, path: String, key: String, body: String?): Int = withContext(Dispatchers.IO) {
        try { call(method, path, key, body); 200 }
        catch (e: RegistryStatus) { e.code }
        catch (e: Exception) { 0 }
    }
    private suspend fun <T> one(method: String, path: String, key: String?, body: String?, s: KSerializer<T>): T =
        withContext(Dispatchers.IO) { json.decodeFromString(s, call(method, path, key, body)) }
    private suspend fun many(path: String, key: String?, s: KSerializer<*>): List<*> =
        withContext(Dispatchers.IO) { json.decodeFromString(ListSerializer(s), call("GET", path, key, null)) }
    private fun enc(value: Any): String = when (value) {
        is RegistryCreate -> json.encodeToString(RegistryCreate.serializer(), value)
        is RegistryVisibility -> json.encodeToString(RegistryVisibility.serializer(), value)
        is LinkRequestInput -> json.encodeToString(LinkRequestInput.serializer(), value)
        is FixtureProposal -> json.encodeToString(FixtureProposal.serializer(), value)
        else -> error("Unsupported registry request ${value::class}")
    }
    suspend fun create(input: RegistryCreate) = one("POST", "/v0/registry/teams", null, enc(input), RegistryCredentials.serializer())
    suspend fun setListed(teamId: String, key: String, listed: Boolean) =
        one("PUT", "/v0/registry/teams/$teamId/listing", key, enc(RegistryVisibility(listed)), RegistryTeam.serializer())
    suspend fun rotate(teamId: String, key: String) = one("POST", "/v0/registry/teams/$teamId/code/rotate", key, "{}", TeamCode.serializer())
    suspend fun revokeCode(teamId: String, key: String) = one("DELETE", "/v0/registry/teams/$teamId/code", key, null, RegistryResult.serializer())
    suspend fun browse(query: String): List<RegistryTeam> =
        many("/v0/registry/teams?q=${URLquery(query)}", null, RegistryTeam.serializer()) as List<RegistryTeam>
    suspend fun inbox(teamId: String, key: String): List<LinkRequestView> =
        many("/v0/registry/teams/$teamId/link-requests", key, LinkRequestView.serializer()) as List<LinkRequestView>
    suspend fun links(teamId: String, key: String): List<LinkView> =
        many("/v0/registry/teams/$teamId/links", key, LinkView.serializer()) as List<LinkView>
    suspend fun schedule(teamId: String, key: String): List<CoordinatedFixture> =
        many("/v0/registry/teams/$teamId/schedule", key, CoordinatedFixture.serializer()) as List<CoordinatedFixture>
    suspend fun fixtures(linkId: String, key: String): List<CoordinatedFixture> =
        many("/v0/links/$linkId/fixtures", key, CoordinatedFixture.serializer()) as List<CoordinatedFixture>
    suspend fun requestLink(teamCode: String, requesterTeamId: String, key: String) =
        one("POST", "/v0/registry/teams/${teamCode.trim()}/link-requests", key,
            enc(LinkRequestInput(requesterTeamId, UUID.randomUUID().toString())), LinkRequestView.serializer())
    suspend fun acceptLink(requestId: String, key: String) =
        one("POST", "/v0/registry/links/$requestId/accept", key, "{}", LinkView.serializer())
    suspend fun propose(linkId: String, key: String, p: FixtureProposal) =
        one("POST", "/v0/links/$linkId/fixtures", key, enc(p), CoordinatedFixture.serializer())
    suspend fun confirm(linkId: String, fixtureId: String, key: String) =
        one("POST", "/v0/links/$linkId/fixtures/$fixtureId/confirm", key, "{}", CoordinatedFixture.serializer())
    companion object {
        fun URLquery(q: String): String = java.net.URLEncoder.encode(q, "UTF-8")
    }
}

@Composable
fun RegistryScreen(store: RoomTeamStore, schedule: com.infinityball.pitchpact.data.RoomScheduleStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("registry_private", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val json = remember { PitchPactJson.codec }
    var endpoint by remember { mutableStateOf(prefs.getString("endpoint", "") ?: "") }
    val teams = remember { store.teams() }
    var selected by remember { mutableStateOf(teams.firstOrNull()?.id ?: "") }
    var credentials by remember(selected) {
        mutableStateOf(prefs.getString("team:$selected", null)?.let {
            runCatching { json.decodeFromString(RegistryCredentials.serializer(), it) }.getOrNull()
        })
    }
    var region by remember { mutableStateOf("") }
    var ageGroup by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var browse by remember { mutableStateOf(emptyList<RegistryTeam>()) }
    var inbox by remember { mutableStateOf(emptyList<LinkRequestView>()) }
    var links by remember { mutableStateOf(emptyList<LinkView>()) }
    var fixtures by remember { mutableStateOf(emptyList<CoordinatedFixture>()) }
    var sharedSchedule by remember { mutableStateOf(emptyList<CoordinatedFixture>()) }
    var kickoff by remember { mutableStateOf((System.currentTimeMillis() + 86_400_000L).toString()) }
    var location by remember { mutableStateOf("") }
    var homeUniform by remember { mutableStateOf("") }
    var awayUniform by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("") }
    val client = remember(endpoint) { RegistryClient(endpoint) }
    fun save(c: RegistryCredentials) {
        credentials = c
        prefs.edit().putString("team:$selected", json.encodeToString(RegistryCredentials.serializer(), c)).apply()
    }
    fun run(action: suspend () -> Unit) = scope.launch {
        try { action(); feedback = "Updated" } catch (e: Exception) { feedback = e.message ?: "Registry failed" }
    }
    val local = store.team(selected)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Team registry & shared fixtures", style = MaterialTheme.typography.titleLarge)
        Text("Opt-in listing only. Roster, guardian contacts and availability never leave this device.")
        OutlinedTextField(endpoint, { endpoint = it; prefs.edit().putString("endpoint", it).apply() },
            label = { Text("Self-hosted server URL (https://…)") })
        teams.forEach { team ->
            TextButton(onClick = { selected = team.id }) {
                Text(if (team.id == selected) "● ${team.name}" else team.name)
            }
        }
        if (local == null) {
            Text("Create a local team first.")
        } else if (credentials == null) {
            OutlinedTextField(region, { region = it }, label = { Text("Region") })
            OutlinedTextField(ageGroup, { ageGroup = it }, label = { Text("Age group (e.g. U10)") })
            Button(onClick = { run {
                save(client.create(RegistryCreate(RegistryListing(local.name, region, ageGroup), UUID.randomUUID().toString())))
            } }) { Text("Opt in & get team code") }
        } else {
            val c = credentials!!
            Text("Share team code: ${c.teamCode}")
            Button(onClick = { run { save(c.copy(teamCode = client.rotate(c.team.id, c.ownerKey).teamCode)) } }) {
                Text("Rotate team code (old code dies now)")
            }
            Button(onClick = { run {
                save(c.copy(team = client.setListed(c.team.id, c.ownerKey, !c.team.listed)))
            } }) { Text(if (c.team.listed) "Hide listing" else "Show listing") }
            OutlinedTextField(query, { query = it }, label = { Text("Find teams") })
            Button(onClick = { run { browse = client.browse(query) } }) { Text("Search registry") }
            browse.forEach { Text("${it.listing.clubName} · ${it.listing.region} · ${it.listing.ageGroup}") }
            OutlinedTextField(code, { code = it }, label = { Text("Other team's code (TEAM-…)") })
            Button(onClick = { run { client.requestLink(code, c.team.id, c.ownerKey) } }) { Text("Request team link") }
            Button(onClick = { run {
                inbox = client.inbox(c.team.id, c.ownerKey)
                links = client.links(c.team.id, c.ownerKey)
                sharedSchedule = client.schedule(c.team.id, c.ownerKey)
            } }) { Text("Refresh links & coordinated schedule") }
            Button(onClick = { run {
                sharedSchedule = client.schedule(c.team.id, c.ownerKey)
                schedule.syncCoordinated(c.team.id, sharedSchedule)
            } }) { Text("Sync coordinated fixtures into my schedule") }
            inbox.filter { it.state == "pending" }.forEach { request ->
                Card { Column(Modifier.padding(8.dp)) {
                    Text("Incoming link request")
                    Button(onClick = { run {
                        client.acceptLink(request.id, c.ownerKey)
                        inbox = client.inbox(c.team.id, c.ownerKey)
                        links = client.links(c.team.id, c.ownerKey)
                    } }) { Text("Accept link") }
                } }
            }
            links.filter { it.state == "active" }.forEach { link ->
                Card(Modifier.padding(vertical = 4.dp)) { Column(Modifier.padding(8.dp)) {
                    Text("Linked team ${if (link.firstTeamId == c.team.id) link.secondTeamId else link.firstTeamId}")
                    Button(onClick = { run { fixtures = client.fixtures(link.id, c.ownerKey) } }) { Text("Review proposals") }
                    fixtures.filter { it.linkId == link.id && it.state == "proposed" }.forEach { fixture ->
                        Text("Proposed: ${fixture.location} · ${java.util.Date(fixture.startEpochMillis)} · " +
                            "home ${fixture.homeUniform} / away ${fixture.awayUniform}")
                        Button(onClick = { run {
                            client.confirm(link.id, fixture.id, c.ownerKey)
                            fixtures = client.fixtures(link.id, c.ownerKey)
                            sharedSchedule = client.schedule(c.team.id, c.ownerKey)
                        } }) { Text("Confirm fixture") }
                    }
                    ConsensusPanel(client, link, c.team.id, c.ownerKey)
                    OutlinedTextField(kickoff, { kickoff = it }, label = { Text("Kickoff epoch millis") })
                    OutlinedTextField(location, { location = it }, label = { Text("Location") })
                    OutlinedTextField(homeUniform, { homeUniform = it }, label = { Text("Home uniform") })
                    OutlinedTextField(awayUniform, { awayUniform = it }, label = { Text("Away uniform") })
                    Button(onClick = { run {
                        val p = FixtureProposal(kickoff.toLong(), location, homeUniform, awayUniform, UUID.randomUUID().toString())
                        val verdict = CoordinationFacade().validateProposal(json.encodeToString(FixtureProposal.serializer(), p), System.currentTimeMillis())
                        require(verdict == "OK") { verdict.removePrefix("ERROR:") }
                        client.propose(link.id, c.ownerKey, p)
                        fixtures = client.fixtures(link.id, c.ownerKey)
                    } }) { Text("Propose fixture (needs both confirms)") }
                } }
            }
            Text("Coordinated schedule (confirmed by both sides only)")
            sharedSchedule.forEach { Text("Coordinated · ${it.location} · ${java.util.Date(it.startEpochMillis)} · ${it.homeUniform} / ${it.awayUniform}") }
        }
        if (feedback.isNotEmpty()) Text(feedback, color = MaterialTheme.colorScheme.error)
    }
}
