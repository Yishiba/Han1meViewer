package com.yenaly.han1meviewer.logic.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable data class SyncValue(val clock: Long, val device: String, val value: JsonElement? = null)
@Serializable data class SyncDocument(val version: Int = 3, val values: Map<String, SyncValue> = emptyMap()) {
    fun validate() {
        require(version in 1..3 && values.size <= 20000)
        values.forEach { (key, value) ->
            require(key.length in 1..500 && value.clock in 1..Long.MAX_VALUE / 2 && value.device.length in 1..100)
            require(key.startsWith("setting/") || key.startsWith("watch/") || key.startsWith("rule/") || key.startsWith("remote/") || key.startsWith("search-alias/") || key.startsWith("search-history/") || key.startsWith("saved-search/"))
        }
    }
}
@Serializable data class SyncBaseline(val document: SyncDocument = SyncDocument(), val local: Map<String, JsonElement> = emptyMap())
data class SyncMerge(val document: SyncDocument, val conflicts: Int)

object SyncMergeEngine {
    /** Three-way merge: only changes since the last applied baseline create local versions/deletions. */
    fun merge(local: Map<String, JsonElement>, baseline: SyncBaseline, remote: SyncDocument, device: String, preferRemote: Boolean): SyncMerge {
        remote.validate(); baseline.document.validate()
        var clock = (remote.values.values + baseline.document.values.values).maxOfOrNull { it.clock } ?: 0L
        val keys = local.keys + baseline.local.keys + remote.values.keys + baseline.document.values.keys
        var conflicts = 0
        val merged = keys.associateWith { key ->
            val old = baseline.document.values[key]
            val incoming = remote.values[key]
            val order = compareBy<SyncValue> { it.clock }.thenBy { it.device }
            if (old != null && incoming != null && order.compare(old, incoming) == 0) require(old == incoming) { "Conflicting revision identity" }
            val known = listOfNotNull(old, incoming).maxWithOrNull(order)
            val localChanged = local[key] != baseline.local[key]
            val remoteChanged = incoming != null && (old == null || order.compare(incoming, old) > 0)
            if (localChanged && remoteChanged && local[key] != incoming.value) {
                val here = local[key]
                if (key.startsWith("search-history/") && here != null && incoming.value != null) {
                    // The key fixes both texts: concurrent use only advances recency, not content.
                    val newest = SearchSyncProjection.newestUse(key, here, incoming.value)
                    if (newest == incoming.value) incoming else SyncValue(++clock, device, newest)
                } else {
                    conflicts++
                    if (preferRemote) incoming else SyncValue(++clock, device, local[key])
                }
            } else if (localChanged) SyncValue(++clock, device, local[key])
            else known
        }.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
        return SyncMerge(SyncDocument(values = merged).also { it.validate() }, conflicts)
    }
    fun live(document: SyncDocument): Map<String, JsonElement> = document.values.mapNotNull { (k, v) -> v.value?.let { k to it } }.toMap()
}

@Serializable private data class EncryptedSync(val version: Int = 1, val salt: String, val iv: String, val ciphertext: String)
object SyncCrypto {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val random = SecureRandom()
    private val aad = "Han1meViewer sync v1".toByteArray()
    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        require(password.length in 12..256 && salt.size == 16) { "Use a shared passphrase of 12–256 characters" }
        val spec = PBEKeySpec(password.toCharArray(), salt, 210000, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword() }
    }
    fun encrypt(document: SyncDocument, password: String): ByteArray {
        document.validate()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)); cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(json.encodeToString(document).toByteArray())
        val encoder = Base64.getEncoder()
        return json.encodeToString(EncryptedSync(salt = encoder.encodeToString(salt), iv = encoder.encodeToString(iv), ciphertext = encoder.encodeToString(encrypted))).toByteArray()
            .also { require(it.size <= 12 * 1024 * 1024) }
    }
    fun decrypt(bytes: ByteArray, password: String): SyncDocument {
        require(bytes.size in 1..12 * 1024 * 1024)
        val envelope = json.decodeFromString<EncryptedSync>(bytes.toString(Charsets.UTF_8))
        require(envelope.version == 1)
        val decoder = Base64.getDecoder()
        val iv = decoder.decode(envelope.iv).also { require(it.size == 12) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, decoder.decode(envelope.salt)), GCMParameterSpec(128, iv)); cipher.updateAAD(aad)
        return json.decodeFromString<SyncDocument>(cipher.doFinal(decoder.decode(envelope.ciphertext)).toString(Charsets.UTF_8)).also { it.validate() }
    }
}
