package com.infinityball.pitchpact.server
import com.infinityball.pitchpact.domain.*
import io.ktor.server.testing.testApplication
import io.ktor.client.request.*
import io.ktor.http.*
import java.nio.file.Files
import kotlin.test.*
import org.junit.Test

class MatchRepositoryTest {
    @Test fun offlineRestartDuplicateConflictRollbackAndAuthorization() = testApplication {
        val directory = Files.createTempDirectory("match-test")
        try {
            val repository = MatchRepository(directory)
            application { appModule(repository) { id, header -> id == "m" && header == "Bearer test-capability" } }
            val goal = LedgerEvent.Event("g", Side.HOME, 0, EventPayload.Goal(Side.HOME, 9))
            var offline = MatchDayState("m").capture(goal)
            var clock = MatchClock()
            for ((index, expected) in listOf(MatchPhase.FIRST_HALF, MatchPhase.HALFTIME, MatchPhase.SECOND_HALF, MatchPhase.STOPPAGE, MatchPhase.FULL_TIME).withIndex()) {
                clock = clock.advance(ClockSample(index * 1000L, index * 1000L, "boot"))
                assertEquals(expected, clock.phase)
                offline = offline.copy(clock = clock).capture(LedgerEvent.Event("e$index", Side.AWAY, index * 1000L, EventPayload.PenaltyMiss(Side.AWAY, index)))
            }
            val restored = MatchDayCodec.decode(MatchDayCodec.encode(offline))
            val request = MatchSyncRequest("m", restored.events)
            suspend fun send(body: MatchSyncRequest, authorized: Boolean = true) = client.post("/v0/matches/m/events") {
                contentType(ContentType.Application.Json)
                if (authorized) header("Authorization", "Bearer test-capability")
                setBody(MatchDayCodec.json.encodeToString(MatchSyncRequest.serializer(), body))
            }
            assertEquals(HttpStatusCode.Forbidden, send(request, false).status)
            assertEquals(HttpStatusCode.OK, send(request).status)
            assertEquals(HttpStatusCode.OK, send(request).status)
            val conflict = request.copy(events = listOf(goal.copy(eventId = "new"), goal.copy(recordedAtEpochMillis = 1)))
            assertEquals(HttpStatusCode.Conflict, send(conflict).status)
            val persisted = MatchDayCodec.decode(Files.readString(directory.resolve("m.json")))
            assertEquals(restored.events, persisted.events)
            assertEquals(request.events, MatchRepository(directory).receive(request).events)
        } finally { directory.toFile().deleteRecursively() }
    }
}
