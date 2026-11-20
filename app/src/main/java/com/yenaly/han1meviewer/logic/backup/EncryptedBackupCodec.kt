package com.yenaly.han1meviewer.logic.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupPasswordRequiredException : IllegalArgumentException("Backup password required")
class BackupUnlockException : IllegalArgumentException("Wrong password or damaged encrypted backup")

@Serializable private data class EncryptedBackup(
    val format: String = EncryptedBackupCodec.FORMAT, val version: Int = 1,
    val kdf: String = "PBKDF2-HMAC-SHA256", val iterations: Int = EncryptedBackupCodec.ITERATIONS,
    val cipher: String = "AES-256-GCM", val salt: String, val iv: String, val ciphertext: String,
)

/** Portable password encryption. A separate domain prevents confusion with sync documents. */
object EncryptedBackupCodec {
    const val FORMAT = "hanime-encrypted-backup"
    const val ITERATIONS = 600000
    const val MAX_FILE_BYTES = ((BackupCodec.MAX_BYTES + 18) / 3) * 4 + 4096
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val random = SecureRandom()
    private val aad = "$FORMAT|1|PBKDF2-HMAC-SHA256|$ITERATIONS|AES-256-GCM".toByteArray()
    fun validatePassword(password: String) { require(password.length in 12..256) }
    private fun derive(password: String, salt: ByteArray): SecretKeySpec {
        validatePassword(password)
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try { SecretKeySpec(bytes, "AES") } finally { bytes.fill(0) }
        } finally { spec.clearPassword() }
    }
    fun encrypt(plain: ByteArray, password: String): ByteArray {
        validatePassword(password)
        BackupCodec.decode(plain)
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv)); cipher.updateAAD(aad)
        val encoder = Base64.getEncoder()
        return json.encodeToString(EncryptedBackup(salt = encoder.encodeToString(salt), iv = encoder.encodeToString(iv),
            ciphertext = encoder.encodeToString(cipher.doFinal(plain)))).toByteArray().also { require(it.size <= MAX_FILE_BYTES) }
    }
    private fun envelope(bytes: ByteArray): EncryptedBackup? {
        require(bytes.size in 1..MAX_FILE_BYTES)
        val document = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        if ((document["format"] as? JsonPrimitive)?.contentOrNull != FORMAT) return null
        return json.decodeFromJsonElement<EncryptedBackup>(document).also {
            require(it.version == 1 && it.kdf == "PBKDF2-HMAC-SHA256" && it.iterations == ITERATIONS && it.cipher == "AES-256-GCM")
            require(it.salt.length == 24 && it.iv.length == 16 && it.ciphertext.length <= MAX_FILE_BYTES)
        }
    }
    fun isEncrypted(bytes: ByteArray): Boolean = envelope(bytes) != null
    fun decode(bytes: ByteArray, password: String? = null): ByteArray {
        val encrypted = envelope(bytes) ?: return bytes.also { BackupCodec.decode(it) }
        if (password == null) throw BackupPasswordRequiredException()
        try {
            validatePassword(password)
            val decoder = Base64.getDecoder()
            val salt = decoder.decode(encrypted.salt).also { require(it.size == 16) }
            val iv = decoder.decode(encrypted.iv).also { require(it.size == 12) }
            val ciphertext = decoder.decode(encrypted.ciphertext).also { require(it.size in 16..BackupCodec.MAX_BYTES + 16) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv)); cipher.updateAAD(aad)
            return cipher.doFinal(ciphertext).also { BackupCodec.decode(it) }
        } catch (_: GeneralSecurityException) { throw BackupUnlockException() }
        catch (_: IllegalArgumentException) { throw BackupUnlockException() }
    }
}
