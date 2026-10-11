package com.infinityball.pitchpact.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.infinityball.pitchpact.dto.BroadcastStarted
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable data class BroadcastCheckpoint(
    val endpoint: String,
    val started: BroadcastStarted,
    val seq: Long = 0,
    val pendingBody: String? = null,
)

/** Encrypted, atomic and excluded from backup. A corrupt checkpoint is never silently reset. */
class BroadcastCheckpointStore(private val context: Context, private val key: () -> SecretKey = ::deviceKey) {
    private fun file(match: String): AtomicFile {
        require(match.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return AtomicFile(File(context.noBackupFilesDir, "broadcast-$match"))
    }

    fun load(match: String): BroadcastCheckpoint? {
        val file = file(match)
        if (!file.baseFile.exists()) return null
        val data = file.openRead().use { it.readBytes() }
        require(data.size > 12) { "Corrupt broadcast checkpoint" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        cipher.updateAAD(match.toByteArray(Charsets.UTF_8))
        return Json.decodeFromString<BroadcastCheckpoint>(cipher.doFinal(data.copyOfRange(12, data.size)).toString(Charsets.UTF_8))
    }

    fun save(match: String, checkpoint: BroadcastCheckpoint?) {
        val file = file(match)
        if (checkpoint == null) { file.delete(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(match.toByteArray(Charsets.UTF_8))
        val data = cipher.iv + cipher.doFinal(Json.encodeToString(checkpoint).toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(data); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}

private fun deviceKey(): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
    (store.getKey("pitchpact-broadcast", null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder("pitchpact-broadcast", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
    }.generateKey()
}
