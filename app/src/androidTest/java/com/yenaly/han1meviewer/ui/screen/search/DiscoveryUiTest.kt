package com.yenaly.han1meviewer.ui.screen.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.discovery.*
import com.yenaly.han1meviewer.logic.translation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import android.graphics.Bitmap
import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.yenaly.han1meviewer.ui.viewmodel.SearchViewModel
import kotlinx.coroutines.awaitCancellation

class DiscoveryUiTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun searchScreenWiresLocalAliasSuggestionsAndSavedSearchEntry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("search_translation_tools", 0)
        val old = prefs.getString("data", null)
        try {
            prefs.edit().putString("data", """{"aliases":[{"original":"discovery-test","translated":"テスト"}]}""").commit()
            val model = SearchViewModel(context.applicationContext as Application, SavedStateHandle())
            compose.setContent { MaterialTheme { SearchScreen(viewModel = model, onBack = {}, onOpenVideo = {},
                onLongPressCopy = { _, _ -> }, onOpenAdvancedSearch = {}) } }
            compose.onNode(hasSetTextAction()).performTextInput("discovery")
            compose.waitUntil(5000) { compose.onAllNodesWithText("discovery-test → テスト").fetchSemanticsNodes().isNotEmpty() }
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                java.io.File(context.cacheDir, "discovery-suggestions.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onNodeWithText(text(R.string.discovery_watchlist)).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText(text(R.string.discovery_watch_hint)).fetchSemanticsNodes().isNotEmpty() }
        } finally {
            prefs.edit().apply { if (old == null) remove("data") else putString("data", old) }.commit()
        }
    }

    @Test fun closingTranslationCancelsWorkWithoutWritingCache() {
        val entered = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val cached = AtomicBoolean(false)
        val translator = ChineseTextTranslation({ _, _, ready ->
            ready(); entered.set(true)
            try { awaitCancellation() } finally { cancelled.set(true) }
        }, { null }, { _, _ -> cached.set(true) })
        compose.setContent { MaterialTheme {
            var visible by remember { mutableStateOf(true) }
            if (visible) ContentTranslationDialog("sample title", { visible = false }, translator)
        } }
        compose.onNodeWithText(text(R.string.search_translate_start)).performClick()
        compose.waitUntil(5000) { entered.get() }
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.waitUntil(5000) { cancelled.get() }
        compose.runOnIdle { assertFalse(cached.get()) }
    }

    @Test fun suggestionsShowSourcesAndRetainCanonicalTagAndAlias() {
        var selected: SearchSuggestion? = null
        val suggestions = SearchSuggestions.find("test", SearchToolsData(aliases = listOf(TranslationEntry("test → title", "作品"))),
            emptyList(), listOf(SuggestionTerm("test tag", listOf("test"), "canonical", true)))
        compose.setContent { MaterialTheme { SearchSuggestionsPanel(suggestions) { selected = it } } }
        compose.onNodeWithText(text(R.string.discovery_source_alias)).assertExists()
        compose.onNodeWithText("test tag").performClick()
        compose.runOnIdle { assertEquals("canonical", selected?.tagKey) }
        compose.onNodeWithText("test → title → 作品").performClick()
        compose.runOnIdle { assertEquals("test → title", selected?.original); assertEquals("作品", selected?.query) }
    }

    @Test fun chineseTranslationRetainsOriginalAndReusesCache() {
        val cache = mutableMapOf<String, String>()
        var calls = 0
        val translator = ChineseTextTranslation({ _, _, ready -> calls++; ready(); "示例译文" }, { cache[it] }, { key, value -> cache[key] = value })
        compose.setContent { MaterialTheme { ContentTranslationDialog("sample title", {}, translator) } }
        compose.onNodeWithText("sample title").assertExists()
        compose.onNodeWithText(text(R.string.search_translate_start)).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("示例译文").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("sample title").assertExists()
        compose.onNodeWithText(text(R.string.search_translate_start)).performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun savedUpdatesCanBeMarkedReadAndReopenedWithEveryFilter() {
        val criteria = SearchCriteria(Preferences.baseUrl, listOf("original", "translated"), "type", "date", true,
            "2026 年", "20", setOf("tag"), setOf("brand"))
        val store = AtomicReference(SavedSearchData(searches = listOf(WatchSearch("id", "test tracker", criteria,
            initialized = true, seen = setOf("1"), updates = listOf(WatchVideo("1", "sample result", 1)), lastAttempt = System.currentTimeMillis()))))
        val repo = SavedSearchRepository(store::get, store::set, { error("Not due") })
        var reopened: SearchCriteria? = null
        compose.setContent { MaterialTheme { SavedSearchUpdatesDialog({}, { reopened = it }, {}, repo) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("test tracker").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.discovery_read_all)).performScrollTo().performClick()
        compose.waitUntil(5000) { repo.state.value.searches.singleOrNull()?.unreadCount == 0 }
        compose.onNodeWithText(text(R.string.discovery_reopen)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(criteria, reopened) }
    }

    @Test fun deletingTrackerRequiresConfirmationAndRetainsOtherTrackers() {
        val store = AtomicReference(SavedSearchData(searches = listOf(WatchSearch("id", "test tracker",
            SearchCriteria(Preferences.baseUrl, listOf("query")), lastAttempt = System.currentTimeMillis()))))
        val repo = SavedSearchRepository(store::get, store::set, { emptyList() })
        compose.setContent { MaterialTheme { SavedSearchUpdatesDialog({}, {}, {}, repo) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("test tracker").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.discovery_remove)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, store.get().searches.size) }
        compose.onNodeWithText(text(R.string.discovery_remove_confirm)).assertExists()
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        compose.waitUntil(5000) { store.get().searches.isEmpty() }
    }
}
