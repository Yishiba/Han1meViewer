package com.yenaly.han1meviewer.logic.translation

import org.junit.Assert.*
import org.junit.Test

class SearchEnhancementsTest {
    @Test fun routesAdvanceIndependentlyAndRetryOnlyFailedPages() {
        val paging = SearchPaging()
        val queries = listOf("original", "translated")
        assertEquals(queries, paging.pending(queries))
        paging.success("original"); paging.fail("translated")
        assertEquals(listOf("translated"), paging.pending(queries))
        assertEquals(2, paging.page("original"))
        assertEquals(1, paging.page("translated"))
        paging.success("translated")
        assertEquals(queries, paging.pending(queries))
        paging.end("original")
        assertEquals(listOf("translated"), paging.pending(queries))
        paging.end("translated")
        assertTrue(paging.complete(queries))
        paging.reset()
        assertFalse(paging.complete(queries))
        assertEquals(1, paging.page("original"))
    }
    @Test fun normalizationDoesNotExpandPartialTitleMatches() {
        assertTrue(SearchTerms.matches("  ＡＢＣ ", listOf("abc")))
        assertFalse(SearchTerms.matches("ab", listOf("abc")))
        assertFalse(SearchTerms.matches("", listOf("")))
    }
    @Test fun languagesAreDetectedConservatively() {
        assertEquals(SourceLanguage.CHINESE, SearchTerms.detect("中文 English"))
        assertEquals(SourceLanguage.ENGLISH, SearchTerms.detect("English"))
        assertNull(SearchTerms.detect("日本語かな"))
        assertNull(SearchTerms.detect("12345"))
    }
    @Test fun combinedQueriesKeepOriginalOrderAndRemoveEquivalentQueries() {
        assertEquals(listOf("abc"), SearchTerms.queries("abc", " ＡＢＣ "))
        assertEquals(listOf("中文", "日本語"), SearchTerms.queries("中文", "日本語"))
    }
}
