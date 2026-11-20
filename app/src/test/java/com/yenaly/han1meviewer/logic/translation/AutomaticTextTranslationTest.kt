package com.yenaly.han1meviewer.logic.translation

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AutomaticTextTranslationTest {
    private val languages = setOf("zh", "en", "ja", "ko", "de")

    @Test fun detectionAndTargetsAreSeparatedInCacheAndSameLanguageIsSkipped() = runBlocking {
        val cache = mutableMapOf<String, String>()
        var identified = 0
        var calls = 0
        val engine = AutomaticTextTranslation(languages, { identified++; "ja" }, { text, source, target, _, ready ->
            calls++; ready(); "$source:$target:$text"
        }, cache::get, { key, text -> cache[key] = text })
        assertEquals("ja:zh:title", engine.translate("title", "zh", false))
        engine.translate("title", "zh", false)
        assertEquals(1, identified)
        assertEquals("ja:en:title", engine.translate("title", "en", false))
        assertNull(engine.translate("title", "ja", false))
        assertEquals(2, calls)
        assertEquals(2, cache.size)
        assertNotEquals(AutomaticTextTranslation.cacheKey("title", "en", "zh"), AutomaticTextTranslation.cacheKey("title", "ja", "zh"))
        assertNotEquals(AutomaticTextTranslation.cacheKey("title", null, "zh"), AutomaticTextTranslation.cacheKey("title", "ja", "zh"))
    }

    @Test fun unknownSourcesNeverReachTranslationOrCache() = runBlocking {
        var calls = 0
        val engine = AutomaticTextTranslation(languages, { null }, { _, _, _, _, _ -> calls++; "bad" }, { null }, { _, _ -> calls++ })
        try { engine.translate("unrecognized", "zh", false); fail() } catch (_: IllegalStateException) { }
        assertEquals(0, calls)
    }

    @Test fun explicitSourceAndMobileConsentReachBackend() = runBlocking {
        var received = ""
        val engine = AutomaticTextTranslation(languages, { error("Do not detect explicit source") }, { _, source, target, mobile, _ ->
            received = "$source:$target:$mobile"; "translation"
        }, { null }, { _, _ -> })
        engine.translate("text", "ko", true, source = "en")
        assertEquals("en:ko:true", received)
    }

    @Test fun parallelIdenticalRequestsAcrossInstancesAreDeduplicated() = runBlocking {
        val cache = mutableMapOf<String, String>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        fun engine() = AutomaticTextTranslation(languages, { "ja" }, { _, _, _, _, _ ->
            calls++; started.complete(Unit); release.await(); "translation"
        }, cache::get, { key, text -> cache[key] = text })
        val first = async { engine().translate("title", "zh", false) }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { engine().translate("title", "zh", false) }
        release.complete(Unit)
        assertEquals("translation", first.await())
        assertEquals("translation", second.await())
        assertEquals(1, calls)
    }

    @Test fun cancellationDiscardsLateBackendResultAndUnblocksNextRequest() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val cache = mutableMapOf<String, String>()
        val engine = AutomaticTextTranslation(languages, { "ja" }, { _, _, target, _, _ ->
            if (target == "zh") withContext(NonCancellable) { started.complete(Unit); finish.await() }
            "translated:$target"
        }, cache::get, { key, text -> cache[key] = text })
        val first = launch { engine.translate("title", "zh", false) }
        started.await()
        first.cancel()
        val second = async { engine.translate("title", "en", false) }
        finish.complete(Unit)
        first.join()
        assertEquals("translated:en", second.await())
        assertNull(cache[AutomaticTextTranslation.cacheKey("title", null, "zh")])
    }

    @Test fun aCachedResultDoesNotWaitBehindModelPreparation() = runBlocking {
        val cache = mutableMapOf(AutomaticTextTranslation.cacheKey("cached", null, "zh") to "ready")
        val entered = CompletableDeferred<Unit>()
        val engine = AutomaticTextTranslation(languages, { "ja" }, { _, _, _, _, _ -> entered.complete(Unit); awaitCancellation() },
            cache::get, { key, value -> cache[key] = value })
        val job = launch { engine.translate("pending", "zh", false) }
        entered.await()
        assertEquals("ready", withTimeout(1000) { engine.translate("cached", "zh", false) })
        job.cancelAndJoin()
    }

    @Test fun invalidTargetsAndOversizeTextAreRejectedBeforeDetection() = runBlocking {
        val engine = AutomaticTextTranslation(languages, { error("Should not identify") }, { _, _, _, _, _ -> error("Should not translate") }, { null }, { _, _ -> })
        for ((text, target) in listOf("title" to "xx", "x".repeat(5001) to "zh", " " to "en")) {
            try { engine.translate(text, target, false); fail() } catch (_: IllegalArgumentException) { }
        }
    }
}
