package com.yenaly.han1meviewer.logic.remote

import com.yenaly.han1meviewer.logic.backup.BackupCodec
import com.yenaly.han1meviewer.logic.translation.*
import kotlinx.serialization.json.*

/** Separate fields allow independent additions and deletions instead of replacing an entire catalog. */
object SearchSyncProjection {
    private val json get() = SyncCrypto.json
    fun aliasKey(entry: TranslationEntry) = "search-alias/" + BackupCodec.digest(SearchTerms.normalize(entry.original).toByteArray())
    fun historyKey(entry: TranslationEntry) = "search-history/" + BackupCodec.digest(
        JsonArray(listOf(JsonPrimitive(entry.original), JsonPrimitive(entry.translated))).toString().toByteArray())
    fun capture(data: SearchToolsData?): Map<String, JsonElement> = buildMap {
        data?.validate()
        data?.aliases?.forEach { put(aliasKey(it), json.encodeToJsonElement(it)) }
        data?.history?.forEach { put(historyKey(it), json.encodeToJsonElement(it)) }
    }
    fun materialize(live: Map<String, JsonElement>): SearchToolsData {
        val aliases = mutableListOf<TranslationEntry>()
        val history = mutableListOf<TranslationEntry>()
        live.forEach { (key, value) ->
            when {
                key.startsWith("search-alias/") -> {
                    val entry = json.decodeFromJsonElement<TranslationEntry>(value)
                    SearchToolsData(aliases = listOf(entry)).validate()
                    require(key == aliasKey(entry))
                    aliases += entry
                }
                key.startsWith("search-history/") -> {
                    val entry = json.decodeFromJsonElement<TranslationEntry>(value)
                    SearchToolsData(history = listOf(entry)).validate()
                    require(key == historyKey(entry))
                    history += entry
                }
            }
        }
        return SearchToolsData(aliases.sortedBy { SearchTerms.normalize(it.original) },
            history.sortedWith(compareByDescending<TranslationEntry> { it.usedAt }.thenBy { it.original }.thenBy { it.translated }).take(50))
            .also { it.validate() }
    }
    fun newestUse(key: String, first: JsonElement, second: JsonElement): JsonElement {
        val entries = listOf(first, second).map { json.decodeFromJsonElement<TranslationEntry>(it) }
        entries.forEach { entry ->
            SearchToolsData(history = listOf(entry)).validate()
            require(key == historyKey(entry))
        }
        return json.encodeToJsonElement(entries.maxBy { it.usedAt })
    }
    fun trimHistory(document: SyncDocument, retained: SearchToolsData, device: String): SyncDocument {
        val kept = retained.history.map(::historyKey).toSet()
        var clock = document.values.values.maxOfOrNull { it.clock } ?: 0L
        val trimmed = document.values.mapValues { (key, value) ->
            if (key.startsWith("search-history/") && value.value != null && key !in kept) SyncValue(++clock, device)
            else value
        }
        return document.copy(values = trimmed).also { it.validate() }
    }
}
