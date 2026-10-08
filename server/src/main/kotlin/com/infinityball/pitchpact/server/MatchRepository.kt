package com.infinityball.pitchpact.server

import com.infinityball.pitchpact.domain.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/** Serialized transactions; temp + fsync + atomic rename preserves old state on failure. */
class MatchRepository(private val directory: Path) {
    init {
        Files.createDirectories(directory)
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
    }
    @Synchronized fun receive(request: MatchSyncRequest): MatchSyncReceipt {
        require(request.matchId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val path = directory.resolve(request.matchId + ".json")
        var state = if (Files.exists(path)) MatchDayCodec.decode(Files.readString(path)) else MatchDayState(request.matchId)
        for (event in request.events) state = state.capture(event)
        val temp = Files.createTempFile(directory, ".transaction-", ".json")
        try {
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"))
            Files.writeString(temp, MatchDayCodec.encode(state.copy(queuedIds = emptyList())))
            FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } finally { Files.deleteIfExists(temp) }
        return MatchSyncReceipt(request.matchId, request.events)
    }
}
