package com.infinityball.pitchpact.data

import com.infinityball.pitchpact.domain.*
import java.net.HttpURLConnection
import java.net.URI

/** Explicit caller-triggered transport. Run I/O off UI, apply verified receipt on UI. */
object MatchDaySync {
    fun send(endpoint: String, capability: String, state: MatchDayState): MatchSyncReceipt {
        val base = URI(endpoint)
        require(base.scheme == "https" || (base.scheme == "http" && base.host in listOf("localhost", "127.0.0.1", "10.0.2.2")))
        require(base.userInfo == null && capability.isNotBlank())
        val request = MatchSyncRequest(state.matchId, state.events.filter { it.eventId in state.queuedIds })
        val connection = base.resolve("/v0/matches/${state.matchId}/events").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.connectTimeout = 10000; connection.readTimeout = 10000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $capability")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(MatchDayCodec.json.encodeToString(MatchSyncRequest.serializer(), request).toByteArray()) }
            require(connection.responseCode == 200) { "Sync failed: ${connection.responseCode}; queue retained" }
            val receipt = MatchDayCodec.json.decodeFromString(MatchSyncReceipt.serializer(), connection.inputStream.bufferedReader().use { it.readText() })
            require(receipt.matchId == request.matchId && receipt.events == request.events) { "Receipt mismatch; queue retained" }
            return receipt
        } finally { connection.disconnect() }
    }
}
