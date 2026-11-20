package com.yenaly.han1meviewer.logic.backup

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EncryptedBackupTest {
    @get:Rule val temp = TemporaryFolder()
    private val password = "test-only-中文-password"
    private val plain get() = BackupCodec.encode(BackupPayload(settings = mapOf("sample" to PreferenceValue.StringValue("private-title-秘密"))))
    private fun change(bytes: ByteArray, key: String, value: JsonElement): ByteArray =
        JsonObject(Json.parseToJsonElement(bytes.decodeToString()).jsonObject + (key to value)).toString().toByteArray()

    @Test fun portableRoundtripUsesFreshSaltAndIvAndHidesRecords() {
        val first = EncryptedBackupCodec.encrypt(plain, password)
        val second = EncryptedBackupCodec.encrypt(plain, password)
        assertFalse(first.contentEquals(second))
        assertFalse(first.decodeToString().contains("private-title"))
        assertFalse(first.decodeToString().contains(password))
        assertTrue(EncryptedBackupCodec.isEncrypted(first))
        assertArrayEquals(plain, EncryptedBackupCodec.decode(first, password))
        val a = Json.parseToJsonElement(first.decodeToString()).jsonObject
        val b = Json.parseToJsonElement(second.decodeToString()).jsonObject
        assertNotEquals(a["salt"], b["salt"])
        assertNotEquals(a["iv"], b["iv"])
    }

    @Test fun missingWrongAndShortPasswordsNeverUnlock() {
        val encrypted = EncryptedBackupCodec.encrypt(plain, password)
        assertThrows(BackupPasswordRequiredException::class.java) { EncryptedBackupCodec.decode(encrypted) }
        assertThrows(BackupUnlockException::class.java) { EncryptedBackupCodec.decode(encrypted, "wrong-password-here") }
        assertThrows(BackupUnlockException::class.java) { EncryptedBackupCodec.decode(encrypted, "short") }
        assertThrows(IllegalArgumentException::class.java) { EncryptedBackupCodec.encrypt(plain, "short") }
        assertThrows(IllegalArgumentException::class.java) { EncryptedBackupCodec.encrypt(plain, "a".repeat(257)) }
    }

    @Test fun modifiedCiphertextSaltIvAndAlgorithmAreRejected() {
        val encrypted = EncryptedBackupCodec.encrypt(plain, password)
        val doc = Json.parseToJsonElement(encrypted.decodeToString()).jsonObject
        for (key in listOf("ciphertext", "salt", "iv")) {
            val original = doc.getValue(key).jsonPrimitive.content
            val tampered = (if (original[0] == 'A') "B" else "A") + original.drop(1)
            assertThrows(BackupUnlockException::class.java) { EncryptedBackupCodec.decode(change(encrypted, key, JsonPrimitive(tampered)), password) }
        }
        for ((key, value) in mapOf("iterations" to JsonPrimitive(Int.MAX_VALUE), "version" to JsonPrimitive(2),
            "cipher" to JsonPrimitive("AES-CBC"), "kdf" to JsonPrimitive("weak"))) {
            assertThrows(IllegalArgumentException::class.java) { EncryptedBackupCodec.decode(change(encrypted, key, value), password) }
        }
        assertThrows(Exception::class.java) { EncryptedBackupCodec.decode(encrypted.copyOf(encrypted.size / 2), password) }
    }

    @Test fun plainAndLegacyFilesRemainCompatibleButMalformedInputIsRejected() {
        assertFalse(EncryptedBackupCodec.isEncrypted(plain))
        assertArrayEquals(plain, EncryptedBackupCodec.decode(plain))
        val legacy = """{"version":1,"settings":{"sample":{"type":"com.yenaly.han1meviewer.logic.BackupManager.PreferenceValue.StringValue","value":"legacy"}}}""".toByteArray()
        assertArrayEquals(legacy, EncryptedBackupCodec.decode(legacy))
        assertThrows(Exception::class.java) { EncryptedBackupCodec.decode(byteArrayOf()) }
        assertThrows(Exception::class.java) { EncryptedBackupCodec.encrypt("{}".toByteArray(), password) }
        assertThrows(Exception::class.java) { BackupCodec.readBounded(byteArrayOf(1, 2).inputStream(), 1) }
    }

    @Test fun rejectedUnlockDoesNotCreateSafetyVersionAndSuccessfulUnlockUsesRollback() = runBlocking {
        val vault = BackupVault(temp.newFolder())
        val before = BackupPayload(settings = mapOf("sample" to PreferenceValue.StringValue("before")))
        var current = before
        var calls = 0
        val backend = object : BackupBackend {
            override suspend fun capture() = current
            override suspend fun apply(payload: BackupPayload) {
                current = payload
                if (++calls == 1) throw java.io.IOException("Injected partial write")
            }
        }
        val repository = BackupRepository(vault, backend)
        val encrypted = EncryptedBackupCodec.encrypt(plain, password)
        try { repository.import(EncryptedBackupCodec.decode(encrypted, "incorrect-password"), 7); fail() }
        catch (_: BackupUnlockException) { }
        assertEquals(0, calls)
        assertTrue(vault.list().isEmpty())
        assertFalse(vault.hasJournal())
        try { repository.import(EncryptedBackupCodec.decode(encrypted, password), 7); fail() }
        catch (_: java.io.IOException) { }
        assertEquals(before, current)
        assertEquals(2, calls)
        assertEquals(BackupKind.BEFORE_RESTORE, vault.list().single().kind)
        assertFalse(vault.hasJournal())
    }
    @Test fun independentReferenceDecryptsUnicodePassword() {
        val bytes = javaClass.getResourceAsStream("/encrypted-backup-reference.hvbenc")!!.use { it.readBytes() }
        val payload = BackupCodec.decode(EncryptedBackupCodec.decode(bytes, "fixture-only-中文-password"))
        assertEquals(PreferenceValue.StringValue("portable 中文 日本語"), payload.settings!!["reference"])
    }

}
