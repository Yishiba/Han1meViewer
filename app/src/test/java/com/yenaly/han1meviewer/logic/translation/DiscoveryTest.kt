package com.yenaly.han1meviewer.logic.translation

import com.yenaly.han1meviewer.logic.discovery.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiscoveryTest {
    @Test fun exactAliasIsNotHiddenBehindEightPartialMatches() {
        val aliases = (1..12).map { TranslationEntry("test$it", "candidate$it") } + TranslationEntry("test", "exact")
        val results = SearchSuggestions.find("test", SearchToolsData(aliases = aliases), emptyList(), emptyList())
        assertEquals("exact", results.first().query)
        assertEquals(8, results.size)
    }
    @Test fun aliasesOutrankHistoryAndEquivalentQueriesAreDeduplicated() {
        val suggestions = SearchSuggestions.find("Ａｌ", SearchToolsData(
            aliases = listOf(TranslationEntry("alpha", "アルファ")),
            history = listOf(TranslationEntry("Alpha", "アルファ"))), listOf("alpha", "alpine"), emptyList())
        assertEquals(SuggestionSource.ALIAS, suggestions.first().source)
        assertEquals(1, suggestions.count { it.query == "アルファ" })
        assertTrue(suggestions.any { it.query == "alpine" })
        assertTrue(SearchSuggestions.find("", SearchToolsData(), listOf("alpha"), emptyList()).isEmpty())
    }

    @Test fun tagSuggestionsKeepCanonicalKeysAndAreBounded() {
        val terms = (1..30).map { SuggestionTerm("tag$it", listOf("标签$it", "tag$it"), "canonical$it", true) }
        val suggestions = SearchSuggestions.find("标签", SearchToolsData(), emptyList(), terms)
        assertEquals(8, suggestions.size)
        assertEquals("canonical1", suggestions.first().tagKey)
    }

    @Test fun firstCheckCreatesBaselineAndLaterChecksOnlyDiscoverUnseenCodes() {
        val first = WatchSearch("id", "name", SearchCriteria("https://example.com/", listOf("term")))
        val baseline = first.record(listOf(WatchVideo("1", "one")), 10)
        assertTrue(baseline.initialized)
        assertTrue(baseline.updates.isEmpty())
        val updated = baseline.record(listOf(WatchVideo("2", "two"), WatchVideo("2", "duplicate"), WatchVideo("1", "one")), 20)
        assertEquals(listOf("2"), updated.updates.map { it.code })
        assertEquals(setOf("1", "2"), updated.seen)
        assertEquals(updated.updates, updated.record(listOf(WatchVideo("2", "two")), 30).updates)
    }

    @Test fun emptyBaselineDoesNotSuppressFutureDiscoveriesAndMarkReadKeepsDeduplication() {
        val watch = WatchSearch("id", "name", SearchCriteria("https://example.com/", listOf("term")))
            .record(emptyList(), 10).record(listOf(WatchVideo("1", "one")), 20)
        assertEquals(1, watch.unreadCount)
        val read = watch.copy(updates = watch.updates.map { it.copy(read = true) })
        assertEquals(0, read.record(listOf(WatchVideo("1", "one")), 30).unreadCount)
    }

    @Test fun completeSearchCriteriaRoundTripsWithoutDroppingFilters() {
        val criteria = SearchCriteria("https://example.com/path/", listOf("中文", "日本語"),
            "genre", "sort", true, "過去一週", "duration", setOf("tag"), setOf("brand"))
        val json = kotlinx.serialization.json.Json
        val result = json.decodeFromString<SearchCriteria>(json.encodeToString(SearchCriteria.serializer(), criteria))
        assertEquals(criteria, result)
        assertNotEquals(criteria, criteria.copy(tags = emptySet()))
    }

    @Test fun translationCacheSeparatesLanguagesAndText() = runBlocking {
        val data = mutableMapOf<String, String>()
        var calls = 0
        val translator = ChineseTextTranslation(
            performTranslation = { text, source, ready -> calls++; ready(); "${source.name}:$text" },
            readCache = { data[it] }, writeCache = { key, text -> data[key] = text })
        assertEquals("JAPANESE:hello", translator.translate("hello", TextSource.JAPANESE) {})
        translator.translate("hello", TextSource.JAPANESE) {}
        translator.translate("hello", TextSource.ENGLISH) {}
        translator.translate("other", TextSource.JAPANESE) {}
        assertEquals(3, calls)
        assertEquals(3, data.size)
    }

    @Test fun translationFailureDoesNotPopulateCache() = runBlocking {
        var saved = false
        val translator = ChineseTextTranslation({ _, _, _ -> error("failure") }, { null }, { _, _ -> saved = true })
        try { translator.translate("text", TextSource.JAPANESE) {}; fail() } catch (_: IllegalStateException) { }
        assertFalse(saved)
    }
}
