package com.yenaly.han1meviewer.logic.remote

import com.yenaly.han1meviewer.logic.backup.*
import com.yenaly.han1meviewer.logic.library.*
import com.yenaly.han1meviewer.logic.entity.WatchHistoryEntity
import com.yenaly.han1meviewer.logic.entity.download.DownloadGroupEntity
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SyncTest {
    private fun value(s: String) = mapOf("setting/theme_color" to JsonPrimitive(s))
    @Test fun unchangedDeviceAcceptsUpdatesWithoutResurrectingDeletedRecords() {
        val initial = SyncMergeEngine.merge(value("a"), SyncBaseline(), SyncDocument(), "A", true).document
        val baseline = SyncBaseline(initial, value("a"))
        val changed = SyncMergeEngine.merge(value("b"), baseline, initial, "A", true).document
        assertEquals(value("b"), SyncMergeEngine.live(SyncMergeEngine.merge(value("a"), baseline, changed, "B", true).document))
        val deleted = SyncMergeEngine.merge(emptyMap(), baseline, initial, "A", true).document
        assertTrue(SyncMergeEngine.live(SyncMergeEngine.merge(value("a"), baseline, deleted, "B", true).document).isEmpty())
        assertNotNull(deleted.values["setting/theme_color"])
    }
    @Test fun concurrentChangesRespectExplicitConflictChoiceAndCount() {
        val initial = SyncMergeEngine.merge(value("a"), SyncBaseline(), SyncDocument(), "A", true).document
        val baseline = SyncBaseline(initial, value("a"))
        val remote = SyncMergeEngine.merge(value("remote"), baseline, initial, "B", true).document
        val remoteWins = SyncMergeEngine.merge(value("local"), baseline, remote, "A", true)
        val localWins = SyncMergeEngine.merge(value("local"), baseline, remote, "A", false)
        assertEquals(1, remoteWins.conflicts)
        assertEquals(value("remote"), SyncMergeEngine.live(remoteWins.document))
        assertEquals(value("local"), SyncMergeEngine.live(localWins.document))
    }
    @Test fun serverRollbackDoesNotOverrideNewerKnownVersion() {
        val old = SyncMergeEngine.merge(value("old"), SyncBaseline(), SyncDocument(), "A", true).document
        val recent = SyncMergeEngine.merge(value("recent"), SyncBaseline(old, value("old")), old, "A", true).document
        val result = SyncMergeEngine.merge(value("local"), SyncBaseline(recent, value("recent")), old, "B", true)
        assertEquals(value("local"), SyncMergeEngine.live(result.document))
        assertEquals(0, result.conflicts)
    }
    @Test fun encryptedPayloadRequiresCorrectPassphraseAndIntegrity() {
        val doc = SyncMergeEngine.merge(value("private"), SyncBaseline(), SyncDocument(), "A", true).document
        val bytes = SyncCrypto.encrypt(doc, "shared-test-passphrase")
        assertFalse(bytes.toString(Charsets.UTF_8).contains("private"))
        assertEquals(doc, SyncCrypto.decrypt(bytes, "shared-test-passphrase"))
        assertThrows(Exception::class.java) { SyncCrypto.decrypt(bytes, "wrong-test-passphrase") }
        val envelope = SyncCrypto.json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val tampered = JsonObject(envelope + ("iv" to JsonPrimitive(java.util.Base64.getEncoder().encodeToString(ByteArray(12)))))
        assertThrows(Exception::class.java) { SyncCrypto.decrypt(tampered.toString().toByteArray(), "shared-test-passphrase") }
        assertThrows(Exception::class.java) { SyncCrypto.encrypt(doc, "short") }
    }
    @Test fun portableRulesMapGroupsByNameAndKeepDevicePathsAndCredentialsLocal() {
        val rules = listOf(SmartCollection("c", "HD", MediaLibraryRule(groupIds = setOf(7)), true, 7))
        val original = BackupPayload(settings = mapOf("cookie" to PreferenceValue.StringValue("secret"),
            "saf_download_path" to PreferenceValue.StringValue("local-device-path"),
            "theme_color" to PreferenceValue.StringValue("blue"),
            SmartCollectionStore.KEY to PreferenceValue.StringValue(MediaLibraryCodec.encodeCollections(rules))),
            downloadGroups = listOf(DownloadGroupEntity("HD", 1, 7)),
            watchHistories = listOf(WatchHistoryEntity("", "Title", 1, 2, "V1", 700, 99)))
        val captured = SyncProjection.capture(original, emptyMap())
        assertFalse(captured.toString().contains("secret")); assertFalse(captured.toString().contains("local-device-path"))
        val target = original.copy(settings = mapOf("saf_download_path" to PreferenceValue.StringValue("other-device-path")),
            downloadGroups = listOf(DownloadGroupEntity("HD", 1, 25)), watchHistories = emptyList())
        val restored = SyncProjection.materialize(target, captured).backup
        assertEquals(PreferenceValue.StringValue("other-device-path"), restored.settings!!["saf_download_path"])
        val mapped = MediaLibraryCodec.decodeCollections((restored.settings!![SmartCollectionStore.KEY] as PreferenceValue.StringValue).value).single()
        assertEquals(setOf(25), mapped.rule.groupIds); assertEquals(25, mapped.autoTargetGroupId); assertTrue(mapped.autoEnabled)
        assertEquals(700L, restored.watchHistories!!.single().progress)
        assertNotEquals(99, restored.watchHistories!!.single().id)
    }
    @Test fun unknownAndIllTypedRemoteSettingsAreRejected() {
        assertThrows(Exception::class.java) { SyncProjection.materialize(BackupPayload(settings = emptyMap()), mapOf("setting/cookie" to JsonPrimitive("secret"))) }
        val bad = SyncCrypto.json.encodeToJsonElement<PreferenceValue>(PreferenceValue.IntValue(99))
        assertThrows(Exception::class.java) { SyncProjection.materialize(BackupPayload(settings = emptyMap()), mapOf("setting/theme_color" to bad)) }
        assertThrows(Exception::class.java) { SyncDocument(values = mapOf("invalid/key" to SyncValue(1, "A", JsonNull))).validate() }
    }
}
