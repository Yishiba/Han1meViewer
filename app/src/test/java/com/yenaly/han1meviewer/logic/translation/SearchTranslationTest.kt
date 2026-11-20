package com.yenaly.han1meviewer.logic.translation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class SearchTranslationTest {
    @Test
    fun `translation preserves original and allows editing the search text`() = runBlocking {
        val session = SearchTranslation("  animation  ", this, KeywordTranslator { text, source, ready ->
            assertEquals("animation", text)
            assertEquals(SourceLanguage.ENGLISH, source)
            ready()
            "アニメ"
        })
        session.selectLanguage(SourceLanguage.ENGLISH)
        session.translate()
        yield()
        assertEquals("  animation  ", session.original)
        assertEquals(TranslationPhase.READY, session.state.value.phase)
        session.editTranslation("アニメーション")
        assertEquals("アニメーション", session.state.value.translation)
        assertEquals("  animation  ", session.original)
    }

    @Test
    fun `download and translation have separate progress states`() = runBlocking {
        val model = CompletableDeferred<Unit>()
        val result = CompletableDeferred<String>()
        val session = SearchTranslation("动画", this, KeywordTranslator { _, source, ready ->
            assertEquals(SourceLanguage.CHINESE, source)
            model.await()
            ready()
            result.await()
        })
        session.translate()
        yield()
        assertEquals(TranslationPhase.DOWNLOADING, session.state.value.phase)
        model.complete(Unit)
        yield()
        assertEquals(TranslationPhase.TRANSLATING, session.state.value.phase)
        result.complete("アニメ")
        yield()
        assertEquals(TranslationPhase.READY, session.state.value.phase)
    }

    @Test
    fun `failed translation can be retried without losing original`() = runBlocking {
        var attempts = 0
        val session = SearchTranslation("动画", this, KeywordTranslator { _, _, ready ->
            if (attempts++ == 0) error("download unavailable")
            ready()
            "アニメ"
        })
        session.translate()
        yield()
        assertEquals(TranslationPhase.ERROR, session.state.value.phase)
        assertEquals("动画", session.original)
        session.translate()
        yield()
        assertEquals("アニメ", session.state.value.translation)
    }

    @Test
    fun `changing language cancels pending result and clears previous translation`() = runBlocking {
        val result = CompletableDeferred<String>()
        val session = SearchTranslation("动画", this, KeywordTranslator { _, _, _ -> result.await() })
        session.translate()
        yield()
        session.selectLanguage(SourceLanguage.ENGLISH)
        result.complete("old result")
        yield()
        assertEquals(TranslationPhase.IDLE, session.state.value.phase)
        assertEquals("", session.state.value.translation)
        assertEquals(SourceLanguage.ENGLISH, session.state.value.source)
    }

    @Test
    fun `blank input never downloads a model`() = runBlocking {
        var called = false
        val session = SearchTranslation("  ", this, KeywordTranslator { _, _, _ ->
            called = true
            ""
        })
        session.translate()
        yield()
        assertFalse(called)
        assertEquals(TranslationPhase.IDLE, session.state.value.phase)
    }
}
