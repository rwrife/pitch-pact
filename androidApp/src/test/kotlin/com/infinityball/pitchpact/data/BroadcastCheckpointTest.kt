package com.infinityball.pitchpact.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.infinityball.pitchpact.dto.BroadcastStarted
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BroadcastCheckpointTest {
    @Test fun restartRetainsExactPendingWriteAndEndpointWithoutPlaintextSecrets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val match = "checkpoint-restart"
        val state = BroadcastCheckpoint("https://example.test", BroadcastStarted("ABC123", "private-session-capability", 999999), 7, "identical-pending-request")
        val store = BroadcastCheckpointStore(context) { key }
        store.save(match, state)
        assertEquals(state, BroadcastCheckpointStore(context) { key }.load(match))
        val bytes = File(context.noBackupFilesDir, "broadcast-$match").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains(state.started.broadcastSessionKey))
        assertFalse(bytes.contains(state.pendingBody!!))
        store.save(match, null)
        assertNull(store.load(match))
    }

    @Test fun corruptCheckpointFailsClosedAndDoesNotReplaceSavedCredentials() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = SecretKeySpec(ByteArray(32), "AES")
        val store = BroadcastCheckpointStore(context) { key }
        val match = "checkpoint-corrupt"
        val state = BroadcastCheckpoint("https://example.test", BroadcastStarted("ABC123", "secret", 999999))
        store.save(match, state)
        val file = File(context.noBackupFilesDir, "broadcast-$match")
        val bytes = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(bytes)
        assertFails { store.load(match) }
        assertContentEquals(bytes, file.readBytes())
        file.delete()
    }
}
