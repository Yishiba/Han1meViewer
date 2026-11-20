package com.yenaly.han1meviewer.logic.translation

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class AutoTranslationConfig(
    val enabled: Boolean = false,
    val target: String = "zh",
    val mobileModels: Boolean = false,
)

/** A request is owned by its visible item. Cancellation must not publish or cache stale results. */
class AutomaticTextTranslation(
    private val supported: Set<String>,
    private val identify: suspend (String) -> String?,
    private val perform: suspend (String, String, String, Boolean, () -> Unit) -> String,
    private val readCache: (String) -> String?,
    private val writeCache: (String, String) -> Unit,
) {
    suspend fun translate(original: String, target: String, mobileModels: Boolean,
                          source: String? = null, ready: () -> Unit = {}): String? {
        require(original.isNotBlank() && original.length <= 5000)
        require(target in supported && (source == null || source in supported))
        val key = cacheKey(original, source, target)
        readCache(key)?.let { coroutineContext.ensureActive(); ready(); return it }
        return requests.withLock {
            // Another visible item may have filled this cache while this request was queued.
            readCache(key)?.let { coroutineContext.ensureActive(); ready(); return@withLock it }
            val detected = source ?: identify(original)
            coroutineContext.ensureActive()
            check(detected != null && detected in supported) { "Unsupported source language" }
            if (detected == target) return@withLock null
            val result = perform(original, detected, target, mobileModels, ready)
            coroutineContext.ensureActive()
            require(result.isNotBlank() && result.length <= 20000)
            writeCache(key, result)
            result
        }
    }

    companion object {
        private val requests = Mutex()
        fun cacheKey(original: String, source: String?, target: String): String = MessageDigest.getInstance("SHA-256")
            .digest("v2:${source ?: "auto"}:$target:$original".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
