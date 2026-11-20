package com.yenaly.han1meviewer.logic.commerce

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

/** Install identity and bearer token stay in no-backup storage; the token is Keystore encrypted. */
class MembershipCredentialStore(context: Context) {
    data class AuthState(val loginTicket: String? = null, val sessionToken: String? = null)

    private val root = context.noBackupFilesDir
    private val deviceFile = File(root, "membership-device-id.txt")
    private val stateFile = File(root, "membership-auth-state.bin")

    fun deviceId(): String = synchronized(lock) {
        if (deviceFile.exists()) {
            val stored = deviceFile.readText()
            if (stored.matches(DEVICE_ID)) return@synchronized stored
        }
        val created = "install-${UUID.randomUUID()}"
        atomicWrite(deviceFile, created.toByteArray(Charsets.US_ASCII))
        created
    }

    fun load(): AuthState = synchronized(lock) {
        if (!stateFile.exists()) return@synchronized AuthState()
        val bytes = stateFile.readBytes()
        require(bytes.size in 29..8192) { "Invalid membership state" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(ALIAS.toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        val json = Json.parseToJsonElement(plain.toString(Charsets.UTF_8)).jsonObject
        AuthState(
            json["ticket"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() },
            json["token"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() },
        )
    }

    fun saveLoginTicket(ticket: String) = synchronized(lock) {
        require(ticket.matches(SECRET))
        save(AuthState(loginTicket = ticket, sessionToken = load().sessionToken))
    }

    fun saveSessionToken(token: String) = synchronized(lock) {
        require(token.matches(SECRET))
        save(AuthState(sessionToken = token))
    }

    fun clear() = synchronized(lock) {
        check(!stateFile.exists() || stateFile.delete())
    }

    private fun save(state: AuthState) {
        val plain = buildJsonObject {
            put("ticket", state.loginTicket.orEmpty())
            put("token", state.sessionToken.orEmpty())
        }.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(ALIAS.toByteArray(Charsets.UTF_8))
        atomicWrite(stateFile, cipher.iv + cipher.doFinal(plain))
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(ALIAS, null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        val temporary = File(file.parentFile, "${file.name}.partial")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        val lock = Any()
        const val ALIAS = "hanime.membership.session.v1"
        val DEVICE_ID = Regex("install-[0-9a-fA-F-]{36}")
        val SECRET = Regex("[A-Za-z0-9_-]{32,128}")
    }
}
