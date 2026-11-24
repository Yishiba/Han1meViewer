package com.yenaly.han1meviewer.logic.remote

import com.yenaly.han1meviewer.logic.backup.*
import com.yenaly.han1meviewer.logic.translation.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SearchSyncTest {
    private fun payload(data: SearchToolsData) = BackupPayload(version = 3, settings = emptyMap(), searchTools = data)
    @Test fun searchBackupRoundtripAndLegacyAbsenceAreDistinct() {
        val data = SearchToolsData(listOf(TranslationEntry("中文", "日本語")), listOf(TranslationEntry("English", "日文", 123)))
        assertEquals(data, BackupCodec.decode(BackupCodec.encode(payload(data))).searchTools)
        assertNull(BackupCodec.decode("""{"version":1,"settings":{}}""".toByteArray()).searchTools)
        assertEquals(SearchToolsData(), BackupCodec.decode(BackupCodec.encode(payload(SearchToolsData()))).searchTools)
        assertThrows(Exception::class.java) { BackupCodec.encode(payload(data.copy(aliases = listOf(TranslationEntry("a", "b"), TranslationEntry("Ａ", "c"))))) }
        assertThrows(Exception::class.java) { BackupCodec.encode(payload(data.copy(history = List(51) { TranslationEntry("$it", "b") }))) }
    }
    @Test fun perEntryMergingPreservesIndependentEditsAndPropagatesDeletions() {
        val a = SearchToolsData(listOf(TranslationEntry("A", "あ")), listOf(TranslationEntry("hello", "こんにちは", 10)))
        val captured = SyncProjection.capture(payload(a), emptyMap())
        val initial = SyncMergeEngine.merge(captured, SyncBaseline(), SyncDocument(), "A", true).document
        val baseline = SyncBaseline(initial, captured)
        val b = a.copy(aliases = a.aliases + TranslationEntry("B", "び"))
        val remote = SyncMergeEngine.merge(SyncProjection.capture(payload(b), emptyMap()), baseline, initial, "B", true).document
        val local = a.copy(aliases = emptyList(), history = a.history + TranslationEntry("other", "別", 20))
        val merged = SyncMergeEngine.merge(SyncProjection.capture(payload(local), emptyMap()), baseline, remote, "A", true)
        assertEquals(0, merged.conflicts)
        val restored = SyncProjection.materialize(payload(a), SyncMergeEngine.live(merged.document)).backup.searchTools!!
        assertEquals(listOf("B"), restored.aliases.map { it.original })
        assertEquals(listOf(20L, 10L), restored.history.map { it.usedAt })
        val again = SyncMergeEngine.merge(captured, baseline, merged.document, "old", true)
        assertEquals(listOf("B"), SyncProjection.materialize(payload(a), SyncMergeEngine.live(again.document)).backup.searchTools!!.aliases.map { it.original })
    }
    @Test fun concurrentAliasEditsAreConflictsAndCapacityIsBounded() {
        val initialData = payload(SearchToolsData(aliases = listOf(TranslationEntry("a", "one"))))
        val first = SyncProjection.capture(initialData, emptyMap())
        val initial = SyncMergeEngine.merge(first, SyncBaseline(), SyncDocument(), "A", true).document
        val baseline = SyncBaseline(initial, first)
        fun edited(text: String) = SyncProjection.capture(payload(SearchToolsData(aliases = listOf(TranslationEntry("a", text)))), emptyMap())
        val remote = SyncMergeEngine.merge(edited("remote"), baseline, initial, "B", true).document
        val merged = SyncMergeEngine.merge(edited("local"), baseline, remote, "A", true)
        assertEquals(1, merged.conflicts)
        assertEquals("remote", SyncProjection.materialize(initialData, SyncMergeEngine.live(merged.document)).backup.searchTools!!.aliases.single().translated)
        val invalid = mapOf("search-alias/bad-hash" to SyncCrypto.json.encodeToJsonElement(TranslationEntry("a", "b")))
        assertThrows(Exception::class.java) { SyncProjection.materialize(initialData, invalid) }
    }
    @Test fun historyTrimmingCreatesTombstonesSoOldDevicesCannotResurrectPrunedEntries() {
        val entries = (1..70).map { TranslationEntry("term$it", "訳$it", it.toLong()) }
        val live = entries.associate { SearchSyncProjection.historyKey(it) to SyncCrypto.json.encodeToJsonElement(it) }
        val document = SyncDocument(values = live.mapValues { SyncValue(1, "A", it.value) })
        val materialized = SyncProjection.materialize(payload(SearchToolsData()), live).backup.searchTools!!
        assertEquals(50, materialized.history.size)
        assertEquals(70L, materialized.history.first().usedAt)
        val trimmed = SearchSyncProjection.trimHistory(document, materialized, "B")
        assertEquals(20, trimmed.values.values.count { it.value == null })
        val oldBaseline = SyncBaseline(document, live)
        val again = SyncMergeEngine.merge(live, oldBaseline, trimmed, "old", true).document
        assertEquals(50, SyncMergeEngine.live(again).size)
    }
    @Test fun oldSyncDocumentsUpgradeAndExcessAliasesAreRejectedWithoutTruncation() {
        val legacy = SyncDocument(version = 1, values = mapOf("setting/theme_color" to
            SyncValue(1, "old", SyncCrypto.json.encodeToJsonElement<PreferenceValue>(PreferenceValue.StringValue("blue")))))
        val data = SearchToolsData(aliases = listOf(TranslationEntry("new", "alias")))
        val merged = SyncMergeEngine.merge(SyncProjection.capture(payload(data), emptyMap()), SyncBaseline(), legacy, "new", true)
        assertEquals(3, merged.document.version)
        val restored = SyncProjection.materialize(payload(data), SyncMergeEngine.live(merged.document)).backup
        assertEquals(data, restored.searchTools)
        val excess = (1..201).associate { index ->
            val entry = TranslationEntry("term$index", "translation$index")
            SearchSyncProjection.aliasKey(entry) to SyncCrypto.json.encodeToJsonElement(entry)
        }
        assertThrows(Exception::class.java) { SyncProjection.materialize(payload(data), excess) }
    }

    @Test fun concurrentReuseOfSameTranslationKeepsNewestTimeWithoutContentConflict() {
        fun terms(time: Long) = SyncProjection.capture(payload(SearchToolsData(history = listOf(TranslationEntry("same", "translation", time)))), emptyMap())
        val initial = SyncMergeEngine.merge(terms(10), SyncBaseline(), SyncDocument(), "A", true).document
        val baseline = SyncBaseline(initial, terms(10))
        val remote = SyncMergeEngine.merge(terms(20), baseline, initial, "B", true).document
        val merged = SyncMergeEngine.merge(terms(30), baseline, remote, "A", true)
        assertEquals(0, merged.conflicts)
        assertEquals(30L, SyncProjection.materialize(payload(SearchToolsData()), SyncMergeEngine.live(merged.document)).backup.searchTools!!.history.single().usedAt)
        val deleted = SyncMergeEngine.merge(emptyMap(), baseline, remote, "A", true)
        assertEquals(1, deleted.conflicts)
    }

}
