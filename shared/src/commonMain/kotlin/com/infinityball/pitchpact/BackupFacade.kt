package com.infinityball.pitchpact

import com.infinityball.pitchpact.domain.*
import kotlinx.serialization.json.Json

/** Swift bridge; called exclusively on the native UI thread. */
class BackupFacade {
    private val codec = Json { encodeDefaults = true }
    fun encode(json: String): String = runCatching { BackupCodec.encode(BackupCodec.decode(json)) }
        .fold({ "OK:$it" }, { "ERROR:${it.message}" })
    fun preview(json: String): String = runCatching { BackupCodec.preview(json) }
        .fold({ "OK:${codec.encodeToString(BackupCounts.serializer(), it)}" }, { "ERROR:${it.message}" })
    fun export(json: String, kind: String): String = runCatching {
        CsvExport.render(kind, BackupCodec.decode(json))
    }.getOrDefault("ERROR:csv export failed")
}

/** CSV for the system share sheet. RFC 4180 quoting; no roster/guardian detail. */
object CsvExport {
    private fun teamsOf(b: BackupV1): Map<String, String> = b.teams.associate { it.id to it.name }

    fun render(kind: String, b: BackupV1): String {
        val teams = teamsOf(b)
        val rows: List<List<String>> = when (kind) {
            "schedule" -> listOf(listOf("Fixture", "Start epoch millis", "Minutes", "Home", "Away", "Status")) +
                b.fixtures.sortedBy(Fixture::startEpochMillis).map {
                    listOf(it.title, it.startEpochMillis.toString(), it.durationMinutes.toString(),
                        teams[it.homeTeamId] ?: it.homeTeamId, teams[it.awayTeamId] ?: it.awayTeamId, it.resultStatus.name.lowercase())
                }
            "results" -> listOf(listOf("Fixture", "Home", "Away", "Official score")) +
                b.fixtures.filter { it.resultStatus == ResultStatus.OFFICIAL }.sortedBy(Fixture::startEpochMillis).map {
                    listOf(it.title, teams[it.homeTeamId] ?: it.homeTeamId, teams[it.awayTeamId] ?: it.awayTeamId,
                        OfficialScore(it.officialHome!!, it.officialAway!!).render())
                }
            "availability" -> listOf(listOf("Fixture", "Yes", "No", "Maybe", "Unanswered")) +
                b.fixtures.map { f ->
                    val r = Scheduling.rollup(f.id, b.players.filter { it.teamId == f.homeTeamId || it.teamId == f.awayTeamId }
                        .map(Player::id), b.availability)
                    listOf(f.title, r.yes.toString(), r.no.toString(), r.maybe.toString(), r.unanswered.toString())
                }
            else -> throw IllegalArgumentException("Unknown CSV export")
        }
        return rows.joinToString("\r\n") { row -> row.joinToString(",") { field ->
            if (field.any { it in "\"\r\n," }) "\"${field.replace("\"", "\"\"")}\"" else field
        } } + "\r\n"
    }
}
