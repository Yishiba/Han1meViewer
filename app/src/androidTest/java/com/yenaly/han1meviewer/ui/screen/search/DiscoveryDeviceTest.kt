package com.yenaly.han1meviewer.ui.screen.search

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.discovery.*
import com.yenaly.han1meviewer.logic.translation.*
import com.yenaly.han1meviewer.ui.screen.settings.remote.DavTlsFixture
import com.yenaly.han1meviewer.ui.viewmodel.SearchViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.EventListener
import okhttp3.Call
import java.net.InetSocketAddress
import java.net.Proxy
import org.junit.Assert.*
import org.junit.Test

class DiscoveryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun chineseTranslationCacheBoundsEntriesAndClearsWithoutDeletingAliases() {
        val prefs = context.getSharedPreferences("content_translation_cache", 0)
        val old = prefs.getString("data", null)
        val aliasPrefs = context.getSharedPreferences("search_translation_tools", 0)
        val oldAliases = aliasPrefs.getString("data", null)
        try {
            val cache = ChineseTranslationCache(context)
            cache.clear()
            repeat(151) { cache.put(ChineseTextTranslation.cacheKey("text$it", TextSource.JAPANESE), "translation$it") }
            assertNull(cache.get(ChineseTextTranslation.cacheKey("text0", TextSource.JAPANESE)))
            assertEquals("translation150", cache.get(ChineseTextTranslation.cacheKey("text150", TextSource.JAPANESE)))
            cache.clear()
            assertNull(cache.get(ChineseTextTranslation.cacheKey("text150", TextSource.JAPANESE)))
            assertEquals(oldAliases, aliasPrefs.getString("data", null))
        } finally {
            prefs.edit().apply { if (old == null) remove("data") else putString("data", old) }.commit()
        }
    }

    @Test fun criteriaRestoreKeepsMergedQueriesDatesAndFilters() {
        val model = SearchViewModel(context.applicationContext as Application, SavedStateHandle())
        val criteria = SearchCriteria(Preferences.baseUrl, listOf("中文", "日本語"), "type", "date", true,
            "2026 年 11 月", "20", setOf("tag"), setOf("brand"))
        InstrumentationRegistry.getInstrumentation().runOnMainSync { model.restoreSavedSearch(criteria) }
        assertEquals(criteria.queries, model.mergedQueries)
        assertEquals(criteria.queries.last(), model.query)
        assertEquals(criteria.date, model.getSearchDate())
        assertEquals(criteria.tags, model.tagMap[0].map { it.searchKey }.toSet())
        assertEquals(criteria.brands, model.brandMap[0].map { it.searchKey }.toSet())
        assertEquals(criteria.genre, model.genre)
        assertEquals(criteria.sort, model.sort)
        assertTrue(model.broad)
    }

    @Test fun savedSearchStorePersistsAndProtectsCorruptData() {
        val prefs = context.getSharedPreferences("saved_search_discovery", 0)
        val old = prefs.getString("data", null)
        try {
            val store = SavedSearchStore(context)
            val data = SavedSearchData(searches = listOf(WatchSearch("id", "test", SearchCriteria("https://example.com/", listOf("query")))))
            store.write(data)
            assertEquals(data, SavedSearchStore(context).read())
            prefs.edit().putString("data", "broken").commit()
            try { store.read(); fail() } catch (_: IllegalArgumentException) { }
            assertEquals("broken", prefs.getString("data", null))
        } finally {
            prefs.edit().apply { if (old == null) remove("data") else putString("data", old) }.commit()
        }
    }

    @Test fun httpsUpdateRequestUsesAllFiltersAndRejectsInvalidResponsesAndOtherSites() = runBlocking {
        DavTlsFixture().use { fixture ->
            val requests = mutableListOf<HttpUrl>()
            val routes = java.util.concurrent.CopyOnWriteArrayList<String>()
            val client = fixture.client.newBuilder().eventListener(object : EventListener() {
                override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                    routes += "${inetSocketAddress.address?.hostAddress}:${inetSocketAddress.port} via ${proxy.type()}"
                }
            }).addInterceptor { chain ->
                requests += chain.request().url
                chain.proceed(chain.request().newBuilder().header("Authorization", Credentials.basic("user", "password")).build())
            }.build()
            fixture.document = """<html><div class="content-padding-new"><div class="horizontal-card">
                <a href="https://hanime1.me/watch?v=123"><img src="https://example.com/cover.jpg"></a>
                <div class="title">Sample title</div><div class="subtitle"><a>Author • today</a></div>
                </div></div></html>""".toByteArray()
            val criteria = SearchCriteria(fixture.root, listOf("中文", "日本語"), "type", "date", true, "2026 年", "20", setOf("tag"), setOf("brand"))
            val results = try { SavedSearchService.fetch(criteria, { fixture.root }, client) }
                catch (error: Exception) { throw AssertionError("HTTPS fixture requests=${fixture.requests}, routes=$routes", error) }
            assertEquals(listOf("123"), results.map { it.code })
            assertEquals(2, requests.size)
            assertEquals("中文", requests[0].queryParameter("query"))
            assertEquals("日本語", requests[1].queryParameter("query"))
            requests.forEach {
                assertEquals("1", it.queryParameter("page")); assertEquals("type", it.queryParameter("genre"))
                assertEquals("on", it.queryParameter("broad")); assertEquals("date", it.queryParameter("sort"))
                assertEquals("2026 年", it.queryParameter("date")); assertEquals("20", it.queryParameter("duration"))
                assertEquals("tag", it.queryParameter("tags[]")); assertEquals("brand", it.queryParameter("brands[]"))
            }
            fixture.getStatus = 403
            try { SavedSearchService.fetch(criteria, { fixture.root }, client); fail() } catch (_: IllegalStateException) { }
            val count = requests.size
            try { SavedSearchService.fetch(criteria, { "https://other.example/" }, client); fail() } catch (_: IllegalStateException) { }
            assertEquals(count, requests.size)
            fixture.getStatus = 200
            fixture.document = "<html>Login required</html>".toByteArray()
            try { SavedSearchService.fetch(criteria, { fixture.root }, client); fail() } catch (_: IllegalStateException) { }
        }
    }

    @Test fun realMlKitJapaneseAndEnglishTranslateToChineseAndCacheIsReusable() = runBlocking {
        withTimeout(120_000) {
            val translator = MlKitChineseTranslator.create(context)
            for ((source, text) in listOf(TextSource.JAPANESE to "こんにちは", TextSource.ENGLISH to "Good morning")) {
                var ready = false
                val result = translator.translate(text, source) { ready = true }
                assertTrue(ready)
                assertTrue(result.any { it in '\u3400'..'\u9fff' })
                assertEquals(result, ChineseTranslationCache(context).get(ChineseTextTranslation.cacheKey(text, source)))
            }
        }
    }
}
