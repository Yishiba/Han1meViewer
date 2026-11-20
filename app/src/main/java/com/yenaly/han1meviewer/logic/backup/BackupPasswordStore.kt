package com.yenaly.han1meviewer.logic.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only the explicit automatic-copy password is retained, in an encrypted no-backup file. */
class BackupPasswordStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "backup-copy-password.bin")
    companion object { private val lock = Any(); private const val ALIAS = "hanime.backup.copy.password.v1" }
    private val aad = ALIAS.toByteArray()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): String = synchronized(lock) {
        require(file.length() in 29..4096)
        val bytes = file.readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))); cipher.updateAAD(aad)
        cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8).also(EncryptedBackupCodec::validatePassword)
    }
    fun save(password: String) = synchronized(lock) {
        EncryptedBackupCodec.validatePassword(password)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(aad)
        val bytes = cipher.iv + cipher.doFinal(password.toByteArray())
        val temporary = File(file.parentFile, "${file.name}.partial")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            check(read() == password)
        } finally { temporary.delete() }
    }
    fun clear() = synchronized(lock) { check(!file.exists() || file.delete()) }
}
