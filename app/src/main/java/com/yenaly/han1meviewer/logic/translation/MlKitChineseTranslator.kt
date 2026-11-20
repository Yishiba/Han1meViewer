package com.yenaly.han1meviewer.logic.translation

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.coroutines.coroutineContext

/** Disposable, bounded cache; neither original text nor cache entries enter backup/sync. */
class ChineseTranslationCache(context: Context) {
    private val prefs = context.getSharedPreferences("content_translation_cache", Context.MODE_PRIVATE)
    companion object { private val lock = Any() }
    private fun read(): Map<String, String> = runCatching {
        val raw = prefs.getString("data", null) ?: return@runCatching emptyMap()
        require(raw.length <= 1024 * 1024)
        Json.decodeFromString<Map<String, String>>(raw).also { entries ->
            require(entries.size <= 150 && entries.all { it.key.length == 64 && it.value.isNotBlank() && it.value.length <= 20000 })
        }
    }.getOrDefault(emptyMap())
    fun get(key: String): String? = synchronized(lock) { read()[key] }
    fun put(key: String, value: String) = synchronized(lock) {
        val data = LinkedHashMap(read().filterKeys { it != key })
        data[key] = value
        while (data.size > 150) data.remove(data.keys.first())
        var raw = Json.encodeToString<Map<String, String>>(data)
        while (raw.length > 1024 * 1024 && data.isNotEmpty()) {
            data.remove(data.keys.first())
            raw = Json.encodeToString<Map<String, String>>(data)
        }
        check(prefs.edit().putString("data", raw).commit())
    }
    fun clear() = synchronized(lock) { check(prefs.edit().clear().commit()) }
}

object MlKitChineseTranslator {
    fun create(context: Context): ChineseTextTranslation {
        val cache = ChineseTranslationCache(context.applicationContext)
        return ChineseTextTranslation(::translate, cache::get, cache::put)
    }

    private suspend fun translate(text: String, source: TextSource, ready: () -> Unit): String = TranslationModels.mutex.withLock {
        val translator = Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(source.code)
            .setTargetLanguage(TranslateLanguage.CHINESE).build())
        try {
            withContext(NonCancellable) {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitResult()
            }
            coroutineContext.ensureActive()
            ready()
            translator.translate(text).awaitResult()
        } finally { translator.close() }
    }
}
