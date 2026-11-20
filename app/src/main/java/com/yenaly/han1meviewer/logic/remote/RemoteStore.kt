package com.yenaly.han1meviewer.logic.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable data class DavProfile(val root: String = "", val username: String = "", val password: String = "", val syncPassphrase: String = "") {
    fun validate() { WebDavClient(root, username, password); require(syncPassphrase.isEmpty() || syncPassphrase.length in 12..256) }
    fun client() = WebDavClient(root, username, password)
}
@Serializable data class RemoteProgress(val url: String, val title: String, val position: Long, val modifiedAt: Long)
@Serializable data class RemoteLocal(
    val deviceId: String = UUID.randomUUID().toString(), val profile: DavProfile = DavProfile(),
    val baseline: SyncBaseline = SyncBaseline(), val progress: Map<String, RemoteProgress> = emptyMap(),
)

/** Keystore-encrypted local file under noBackupFilesDir: no passwords in normal preferences/backups. */
class RemoteStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "remote-library.bin")
    companion object { private val lock = Any(); private const val ALIAS = "hanime.remote.library.v1" }
    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (keystore.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): RemoteLocal = synchronized(lock) {
        if (!file.exists()) return@synchronized RemoteLocal()
        require(file.length() in 29..32L * 1024 * 1024)
        val bytes = file.readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        SyncCrypto.json.decodeFromString<RemoteLocal>(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
    }
    fun update(action: (RemoteLocal) -> RemoteLocal): RemoteLocal = synchronized(lock) {
        val previous = read()
        val data = action(previous)
        if (data == previous) return@synchronized data
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(SyncCrypto.json.encodeToString(data).toByteArray())
        require(bytes.size <= 32 * 1024 * 1024)
        val temporary = File(file.parentFile, "${file.name}.partial")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
        data
    }
    fun saveProfile(profile: DavProfile) = update {
        profile.validate()
        if (it.profile.root == profile.root && it.profile.username == profile.username && it.profile.syncPassphrase == profile.syncPassphrase)
            it.copy(profile = profile)
        else it.copy(profile = profile, baseline = SyncBaseline())
    }
    fun saveProgress(progress: RemoteProgress, expectedProfile: DavProfile? = null) = update {
        if (expectedProfile != null && it.profile != expectedProfile) return@update it
        require(progress.position >= 0 && progress.url.length <= 4000)
        it.copy(progress = (it.progress + (progress.url to progress)).entries.sortedByDescending { entry -> entry.value.modifiedAt }.take(2000).associate { entry -> entry.toPair() })
    }
    fun clear() = synchronized(lock) { check(!file.exists() || file.delete()) }
}
