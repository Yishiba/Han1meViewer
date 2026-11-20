package com.yenaly.han1meviewer.ui.screen.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import com.yenaly.han1meviewer.logic.model.*
import com.yenaly.han1meviewer.logic.translation.*
import com.yenaly.han1meviewer.ui.navigation.settings.SettingsPreferenceKeys as K
import com.yenaly.han1meviewer.ui.screen.home.homepage.*
import com.yenaly.han1meviewer.ui.screen.home.homepage.component.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HomeAutomaticTranslationUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun videos(prefix: String, count: Int = 1) = (0 until count).map {
        HanimeInfo("$prefix$it", "", "$prefix$it", itemType = HanimeInfo.NORMAL)
    }
    private fun settings(action: () -> Unit) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val keys = setOf(K.AUTO_TRANSLATE_ENABLED, K.AUTO_TRANSLATE_TARGET, K.HOME_CATEGORY_ORDER, K.HOME_CATEGORY_HIDDEN)
        val old = prefs.all.filterKeys { it in keys }
        prefs.edit().putBoolean(K.AUTO_TRANSLATE_ENABLED, true).putString(K.AUTO_TRANSLATE_TARGET, "zh")
            .remove(K.HOME_CATEGORY_ORDER).remove(K.HOME_CATEGORY_HIDDEN).commit()
        try { action() } finally {
            val editor = prefs.edit()
            keys.forEach(editor::remove)
            old.forEach { (key, value) -> when (value) { is String -> editor.putString(key, value); is Boolean -> editor.putBoolean(key, value) } }
            editor.commit()
        }
    }
    private fun engine(requests: MutableList<String>) = AutomaticTextTranslation(setOf("ja", "zh", "en"), { "ja" },
        { text, _, target, _, _ -> requests += text; "$target:$text" }, { null }, { _, _ -> })

    @Test fun horizontalCategoriesTranslateOnlyVisibleCardsAndKeepVideoNavigation() = settings {
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val backend = engine(requests)
        var opened: String? = null
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalAutomaticTranslationEngine provides backend) {
            CategoryRow("Sample category", videos("Card", 24), {}, { opened = it }, { _, _ -> })
        } } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("zh:Card0").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(requests.size < 24)
        compose.onNodeWithText("Card0").performClick()
        compose.runOnIdle { assertEquals("Card0", opened) }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(20)
        compose.waitUntil(5000) { requests.contains("Card20") }
    }

    @Test fun inactiveCategoryDoesNotTranslateAndDisablingRemovesTranslations() = settings {
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val backend = engine(requests)
        val active = mutableStateOf(false)
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalAutomaticTranslationEngine provides backend) {
            CategoryRow("Sample category", videos("Card"), {}, {}, { _, _ -> }, autoTranslationActive = active.value)
        } } }
        compose.waitForIdle()
        assertTrue(requests.isEmpty())
        compose.runOnIdle { active.value = true }
        compose.waitUntil(5000) { compose.onAllNodesWithText("zh:Card0").fetchSemanticsNodes().isNotEmpty() }
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(K.AUTO_TRANSLATE_ENABLED, false).commit()
        compose.waitUntil(5000) { compose.onAllNodesWithText("zh:Card0").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Card0").assertExists()
    }

    @Test fun bannerUsesSelectedPageAndRetainsOriginal() = settings {
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val backend = engine(requests)
        val banners = listOf(HomePage.Banner("Banner A", null, "", "1"), HomePage.Banner("Banner B", null, "", "2"))
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalAutomaticTranslationEngine provides backend) {
            BannerCarousel(banners, {})
        } } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("zh:Banner A").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Banner A").assertExists()
        assertFalse(requests.contains("Banner B"))
        compose.onNodeWithTag("home-banner-pager").performTouchInput { swipeLeft() }
        compose.waitUntil(5000) { compose.onAllNodesWithText("zh:Banner B").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("zh:Banner A").assertDoesNotExist()
    }

    @Test fun homePageWiresVerticalSectionVisibilityAndTargetLanguageChanges() = settings {
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val backend = engine(requests)
        val page = HomePage(csrfToken = null, avatarUrl = null, username = null,
            banner = HomePage.Banner("Home banner", null, "", "1"), latestHanime = mutableListOf(),
            latestRelease = videos("Release").toMutableList(), ecchiAnime = videos("First").toMutableList(),
            shortEpisodeAnime = videos("Short").toMutableList(), twoPointFiveDAnime = mutableListOf(),
            threeDCG = videos("ThreeD").toMutableList(), motionAnime = videos("Motion").toMutableList(),
            twoDAnime = mutableListOf(), aiGenerated = mutableListOf(), mmd = mutableListOf(),
            cosplay = videos("Far").toMutableList(), watchingNow = mutableListOf(), newAnimeTrailer = mutableListOf(), userId = "")
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalAutomaticTranslationEngine provides backend) {
            HomePageContent(HomeData(page, emptyList()), {}, {}, Modifier.testTag("home-list"))
        } } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("zh:Home banner").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(requests.contains("Far0"))
        compose.onNodeWithTag("home-list").performScrollToIndex(6)
        compose.waitUntil(5000) { compose.onAllNodesWithText("zh:Far0").fetchSemanticsNodes().isNotEmpty() }
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(K.AUTO_TRANSLATE_TARGET, "en").commit()
        compose.waitUntil(5000) { compose.onAllNodesWithText("en:Far0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Far0").assertExists()
        compose.onNodeWithText("zh:Far0").assertDoesNotExist()
    }
}
