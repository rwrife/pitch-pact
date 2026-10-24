package com.infinityball.pitchpact.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

/** Device-only, atomic outbox. One queue per server/link/team; never includes owner capability. */
class ConsensusOutboxStore(private val context: Context) {
    private fun file(endpoint: String, link: String, team: String): AtomicFile {
        val digest = MessageDigest.getInstance("SHA-256").digest("$endpoint|$link|$team".toByteArray(Charsets.UTF_8))
        return AtomicFile(File(context.noBackupFilesDir, "consensus-${digest.joinToString("") { "%02x".format(it) }}"))
    }
    fun load(endpoint: String, link: String, team: String): String? {
        val file = file(endpoint, link, team)
        return if (file.baseFile.exists()) file.openRead().bufferedReader().use { it.readText() } else null
    }
    fun save(endpoint: String, link: String, team: String, json: String) {
        val file = file(endpoint, link, team)
        val stream = file.startWrite()
        try { stream.write(json.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
