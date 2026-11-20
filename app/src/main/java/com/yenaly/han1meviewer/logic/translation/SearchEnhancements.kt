package com.yenaly.han1meviewer.logic.translation

import java.text.Normalizer
import java.util.Locale
import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object SearchTerms {
    fun normalize(text: String) = Normalizer.normalize(text.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    fun detect(text: String): SourceLanguage? {
        if (text.any { it in '\u3040'..'\u30ff' }) return null
        if (text.any { it in '\u3400'..'\u9fff' }) return SourceLanguage.CHINESE
        if (text.any { it in 'a'..'z' || it in 'A'..'Z' }) return SourceLanguage.ENGLISH
        return null
    }
    fun matches(input: String, names: List<String>) = input.isNotBlank() && names.any { normalize(it) == normalize(input) }
    fun queries(original: String, translated: String) = listOf(original.trim(), translated.trim())
        .filter { it.isNotEmpty() }.distinctBy(::normalize)
}

@Serializable data class TranslationEntry(val original: String, val translated: String, val usedAt: Long = 0)
@Serializable data class SearchToolsData(val aliases: List<TranslationEntry> = emptyList(), val history: List<TranslationEntry> = emptyList()) {
    fun validate() {
        require(aliases.size <= 200 && history.size <= 50)
        fun entry(e: TranslationEntry, limit: Int) {
            require(e.original.isNotBlank() && e.original.length <= 500 && e.translated.isNotBlank() && e.translated.length <= limit)
            require(e.usedAt in 0..253402300799999L)
        }
        aliases.forEach { entry(it, 500) }; history.forEach { entry(it, 2000) }
        require(aliases.distinctBy { SearchTerms.normalize(it.original) }.size == aliases.size)
        require(history.distinctBy { it.original to it.translated }.size == history.size)
    }
}

/** User writes share the restore gate; replacement runs only inside the protected backup path. */
class SearchToolsStore(private val context: Context,
    private val prefs: android.content.SharedPreferences = context.getSharedPreferences("search_translation_tools", Context.MODE_PRIVATE)) {
    companion object { private val lock = Any() }
    private val json = Json { ignoreUnknownKeys = true }
    fun read(): SearchToolsData = synchronized(lock) {
        prefs.getString("data", null)?.let {
            require(it.length <= 1024 * 1024)
            val data = json.decodeFromString<SearchToolsData>(it)
            data.copy(history = data.history.mapIndexed { index, entry ->
                if (entry.usedAt == 0L) entry.copy(usedAt = (data.history.size - index).toLong()) else entry
            }).also { value -> value.validate() }
        } ?: SearchToolsData()
    }
    internal fun snapshot(): Pair<SearchToolsData?, String?> = synchronized(lock) {
        try { read() to null } catch (_: IllegalArgumentException) {
            val raw = requireNotNull(prefs.getString("data", null))
            require(raw.length <= 1024 * 1024)
            null to raw
        }
    }
    internal fun replaceRaw(raw: String) = synchronized(lock) {
        require(raw.length <= 1024 * 1024)
        check(prefs.edit().putString("data", raw).commit())
    }
    internal fun replace(data: SearchToolsData) = synchronized(lock) {
        data.validate()
        val encoded = json.encodeToString(data)
        require(encoded.length <= 1024 * 1024)
        check(prefs.edit().putString("data", encoded).commit())
    }
    private suspend fun mutate(action: (SearchToolsData) -> SearchToolsData) =
        com.yenaly.han1meviewer.logic.backup.BackupService.get(context).withSearchToolsAccess {
            synchronized(lock) { replace(action(read())) }
        }
    suspend fun saveAlias(original: String, translated: String) = mutate { data ->
        require(original.isNotBlank() && translated.isNotBlank() && original.length <= 500 && translated.length <= 500)
        data.copy(aliases = data.aliases.filterNot { SearchTerms.normalize(it.original) == SearchTerms.normalize(original) } +
            TranslationEntry(original.trim(), translated.trim()))
    }
    suspend fun remember(original: String, translated: String) = mutate { data ->
        require(original.isNotBlank() && translated.isNotBlank() && original.length <= 500 && translated.length <= 2000)
        data.copy(history = (listOf(TranslationEntry(original, translated, maxOf(System.currentTimeMillis(), (data.history.maxOfOrNull { it.usedAt } ?: 0L) + 1).coerceAtMost(253402300799999L))) + data.history)
            .distinctBy { it.original to it.translated }.take(50))
    }
    suspend fun remove(entry: TranslationEntry, alias: Boolean) = mutate { data ->
        if (alias) data.copy(aliases = data.aliases.filterNot { SearchTerms.normalize(it.original) == SearchTerms.normalize(entry.original) })
        else data.copy(history = data.history.filterNot { it.original == entry.original && it.translated == entry.translated })
    }
    suspend fun clearHistory() = mutate { it.copy(history = emptyList()) }
}
