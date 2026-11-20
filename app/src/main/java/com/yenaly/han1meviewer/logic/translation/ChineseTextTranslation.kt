package com.yenaly.han1meviewer.logic.translation

import kotlinx.coroutines.ensureActive
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

enum class TextSource(val code: String) { JAPANESE("ja"), ENGLISH("en"), CHINESE("zh") }

class ChineseTextTranslation(
    private val performTranslation: suspend (String, TextSource, () -> Unit) -> String,
    private val readCache: (String) -> String?,
    private val writeCache: (String, String) -> Unit,
) {
    suspend fun translate(original: String, source: TextSource, ready: () -> Unit): String {
        require(original.isNotBlank() && original.length <= 5000)
        val key = cacheKey(original, source)
        readCache(key)?.let { ready(); return it }
        val result = performTranslation(original, source, ready)
        coroutineContext.ensureActive()
        require(result.isNotBlank() && result.length <= 20000)
        writeCache(key, result)
        return result
    }

    companion object {
        fun cacheKey(original: String, source: TextSource): String = MessageDigest.getInstance("SHA-256")
            .digest("v1:$source:zh:$original".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
