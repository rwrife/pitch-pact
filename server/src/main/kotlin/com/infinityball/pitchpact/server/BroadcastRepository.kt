package com.infinityball.pitchpact.server

import com.infinityball.pitchpact.dto.*
import com.infinityball.pitchpact.domain.*
import kotlinx.serialization.Serializable
import java.nio.file.*
import java.nio.channels.FileChannel
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom

@Serializable private data class BroadcastReceipt(val key: String, val digest: String, val ack: BroadcastAck)
@Serializable private data class BroadcastRecord(
    val create: BroadcastCreate,
    val started: BroadcastStarted,
    val state: MatchDayState,
    val seq: Long = 0,
    val lastUpdate: BroadcastUpdate? = null,
    val receipts: List<BroadcastReceipt> = emptyList(),
    val finishedAt: Long? = null,
    val stopped: Boolean = false,
)
class MissingBroadcast : RuntimeException()
class UnauthorizedBroadcast : RuntimeException()

/** One process owns this directory. Atomic, fsynced writes; no spectator identities on disk. */
class BroadcastRepository(private val directory: Path, private val now: () -> Long = System::currentTimeMillis) {
    private val json = PitchPactJson.codec
    private val random = SecureRandom()
    private val records = mutableMapOf<String, BroadcastRecord>()
    init {
        Files.createDirectories(directory)
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        Files.newDirectoryStream(directory, "*.json").use { files -> files.forEach { path ->
            val record = json.decodeFromString(BroadcastRecord.serializer(), Files.readString(path))
            if (record.started.expiresAt > now()) records[record.started.spectatorCode] = record
            else Files.delete(path)
        } }
    }
    private fun token(length: Int, alphabet: String): String = (1..length).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    private fun save(record: BroadcastRecord) {
        val path = directory.resolve(record.started.spectatorCode + ".json")
        val temp = Files.createTempFile(directory, ".broadcast-", ".tmp")
        try {
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"))
            Files.writeString(temp, json.encodeToString(BroadcastRecord.serializer(), record))
            FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
            records[record.started.spectatorCode] = record
        } finally { Files.deleteIfExists(temp) }
    }
    private fun get(code: String): BroadcastRecord {
        require(code.matches(Regex("[A-Z0-9]{6}")))
        val record = records[code] ?: throw MissingBroadcast()
        if (record.started.expiresAt <= now()) {
            Files.deleteIfExists(directory.resolve(code + ".json")); records.remove(code)
            throw MissingBroadcast()
        }
        if (record.stopped) throw MissingBroadcast()
        return record
    }
    private fun authorized(code: String, key: String?): BroadcastRecord = get(code).also {
        if (key == null || !MessageDigest.isEqual(key.toByteArray(), it.started.broadcastSessionKey.toByteArray())) throw UnauthorizedBroadcast()
    }
    @Synchronized fun create(input: BroadcastCreate): BroadcastStarted {
        require(input.matchId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(input.homeTeam.isNotBlank() && input.homeTeam.length <= 120 && input.awayTeam.isNotBlank() && input.awayTeam.length <= 120)
        require(input.fieldPolicy in listOf("full", "number-only") && input.idempotencyKey.length in 16..128)
        require(input.teamLinkId?.matches(Regex("[A-Za-z0-9_-]{1,128}")) != false)
        records.values.firstOrNull { it.create.idempotencyKey == input.idempotencyKey && it.started.expiresAt > now() }?.let {
            require(it.create == input && !it.stopped) { "Conflicting create replay" }
            return it.started
        }
        var code: String
        do { code = token(6, "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789") } while (records.containsKey(code))
        val started = BroadcastStarted(code, "BSK-" + token(48, "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"), now() + 24 * 60 * 60 * 1000L)
        save(BroadcastRecord(input, started, MatchDayState(input.matchId)))
        return started
    }
    @Synchronized fun fieldPolicy(code: String, key: String?): String = authorized(code, key).create.fieldPolicy
    @Synchronized fun update(code: String, key: String?, update: BroadcastUpdate): BroadcastAck {
        val previous = authorized(code, key)
        require(update.idempotencyKey.length in 16..128 && update.events.size <= 2000 && update.decisions.size <= 2000)
        val digest = MessageDigest.getInstance("SHA-256").digest(json.encodeToString(BroadcastUpdate.serializer(), update).toByteArray())
            .joinToString("") { "%02x".format(it) }
        previous.receipts.firstOrNull { it.key == update.idempotencyKey }?.let { prior ->
            require(prior.digest == digest) { "Conflicting replay for idempotency key" }
            return prior.ack
        }
        require(update.seq == previous.seq + 1 && update.seq <= 10_000) { "Out-of-order or exhausted sequence" }
        // M7 owns authenticated opposing-side decisions. A broadcast capability may NOT manufacture acceptance.
        require(update.decisions.all { it.state == ConsensusState.PENDING }) { "Consensus resolution requires opposing-side authorization" }
        require(update.events.take(previous.state.events.size) == previous.state.events) { "Broadcast ledger is append-only" }
        require(update.decisions.containsAll(previous.state.decisions))
        require(update.events.none { it is LedgerEvent.Event && it.eventId.isBlank() })
        require(update.decisions.map { it.change.changeId }.distinct().size == update.decisions.size)
        require(previous.state.clock.phase != MatchPhase.FULL_TIME || update.clock == previous.state.clock)
        require(update.clock.elapsedMillis >= previous.state.clock.elapsedMillis)
        val state = MatchDayCodec.decode(MatchDayCodec.encode(MatchDayState(previous.create.matchId,
            events = update.events, clock = update.clock, decisions = update.decisions)))
        val finishedAt = previous.finishedAt ?: if (state.clock.phase == MatchPhase.FULL_TIME) now() else null
        val expires = finishedAt?.plus(60 * 60 * 1000L) ?: previous.started.expiresAt
        val ack = BroadcastAck(update.seq, expires)
        // At most 10,000 writes per session; retain every receipt through expiry and restart.
        save(previous.copy(state = state, seq = update.seq, lastUpdate = update, finishedAt = finishedAt,
            receipts = previous.receipts + BroadcastReceipt(update.idempotencyKey, digest, ack),
            started = previous.started.copy(expiresAt = expires)))
        return ack
    }
    @Synchronized fun stop(code: String, key: String?) { save(authorized(code, key).copy(stopped = true)) }
    @Synchronized fun rotate(code: String, key: String?): BroadcastStarted {
        val previous = authorized(code, key)
        val started = previous.started.copy(broadcastSessionKey = "BSK-" + token(48, "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"))
        save(previous.copy(started = started))
        return started
    }
    @Synchronized fun spectate(code: String, count: Int = 0): SpectatorView {
        val record = get(code)
        val score = record.state.officialScore()
        val pending = record.state.decisions.filter { it.state == ConsensusState.PENDING }.map {
            PendingChangeView(it.change.changeId, it.change.submittedBy, it.state)
        }
        return SpectatorView(record.create.homeTeam, record.create.awayTeam, record.state.clock,
            ScoreboardPayload(record.create.matchId, score.home, score.away, pending), pending,
            record.state.events, record.started.expiresAt, count)
    }
}

/** Bounded, short-lived salted digests only. No IP addresses/identity/history persisted or logged. */
class BroadcastTraffic(private val now: () -> Long = System::currentTimeMillis) {
    private val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private data class Window(var start: Long, var requests: Int)
    private val limits = mutableMapOf<String, Window>()
    private val viewers = mutableMapOf<String, MutableMap<String, Long>>()
    @Synchronized fun allow(scope: String, identity: String, maximum: Int): Boolean {
        limits.entries.removeIf { now() - it.value.start >= 60_000 }
        if (limits.size >= 10000) return false
        val digest = MessageDigest.getInstance("SHA-256").digest(salt + identity.toByteArray()).joinToString("") { "%02x".format(it) }
        val window = limits.getOrPut("$scope:$digest") { Window(now(), 0) }
        return ++window.requests <= maximum
    }
    @Synchronized fun count(code: String, ip: String? = null): Int {
        viewers.values.forEach { map -> map.entries.removeIf { now() - it.value > 30_000 } }
        viewers.entries.removeIf { it.value.isEmpty() }
        if (ip != null && viewers.size < 2000) {
            val digest = MessageDigest.getInstance("SHA-256").digest(salt + ip.toByteArray()).joinToString("") { "%02x".format(it) }
            val active = viewers.getOrPut(code) { mutableMapOf() }
            if (active.size < 2000) active[digest] = now()
        }
        return viewers[code]?.size ?: 0
    }
}
