package com.infinityball.pitchpact.data

import com.infinityball.pitchpact.dto.*
import java.net.HttpURLConnection
import java.net.URI

/** Blocking transport: callers must dispatch off main. Never forward capability on redirect. */
object BroadcastTransport {
    private fun url(base: String, path: String): java.net.URL {
        val uri = URI(base)
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in listOf("localhost", "127.0.0.1", "10.0.2.2")))
        require(uri.userInfo == null && uri.host != null && uri.rawQuery == null && uri.rawFragment == null)
        return uri.resolve(path).toURL()
    }
    fun request(base: String, method: String, path: String, body: String? = null, key: String? = null): Pair<Int, String> {
        val connection = url(base, path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            if (key != null) connection.setRequestProperty("Authorization", "Bearer $key")
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            return connection.responseCode to if (connection.responseCode in 200..299) connection.inputStream.bufferedReader().use { it.readText() } else ""
        } finally { connection.disconnect() }
    }
    fun start(base: String, capability: String, body: String): BroadcastStarted {
        val (code, response) = request(base, "POST", "/v0/broadcasts", body, capability)
        require(code == 200) { "Start failed: HTTP $code" }
        return PitchPactJson.codec.decodeFromString(BroadcastStarted.serializer(), response)
    }
    fun push(base: String, started: BroadcastStarted, body: String): BroadcastAck {
        val (code, response) = request(base, "PATCH", "/v0/broadcasts/${started.spectatorCode}", body, started.broadcastSessionKey)
        require(code == 200) { "Broadcast push failed: HTTP $code" }
        return PitchPactJson.codec.decodeFromString(BroadcastAck.serializer(), response)
    }
    fun watch(base: String, code: String): String {
        require(code.matches(Regex("[A-Z0-9]{6}")))
        val (status, response) = request(base, "GET", "/v0/spectate/$code")
        require(status == 200) { "Watch failed: HTTP $status" }
        return response
    }
    fun stop(base: String, started: BroadcastStarted) {
        val (status, _) = request(base, "DELETE", "/v0/broadcasts/${started.spectatorCode}", key = started.broadcastSessionKey)
        require(status == 204) { "Stop failed: HTTP $status" }
    }
}
