package com.yenaly.han1meviewer.ui.screen.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.translation.KeywordTranslator
import com.yenaly.han1meviewer.logic.translation.SourceLanguage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SearchTranslationDialogTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun editJapaneseAndSearchWithoutChangingOriginal() {
        var submitted: String? = null
        var source: SourceLanguage? = null
        compose.setContent {
            MaterialTheme {
                SearchTranslationDialog(
                    original = "animation", onDismiss = {}, onSearchOriginal = {},
                    onSearchJapanese = { submitted = it },
                    translator = KeywordTranslator { _, language, ready ->
                        source = language
                        ready()
                        "アニメ"
                    },
                )
            }
        }
        compose.onNodeWithText(text(R.string.search_translate_search_japanese)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.search_translate_english)).performClick()
        compose.onNodeWithText(text(R.string.search_translate_start)).performScrollTo().performClick()
        compose.onNodeWithText("アニメ").performScrollTo().performTextReplacement("アニメーション")
        compose.onNodeWithText("animation").assertExists()
        compose.onNodeWithText(text(R.string.search_translate_search_japanese)).performClick()
        compose.waitUntil(5000) { submitted != null }
        compose.runOnIdle {
            assertEquals(SourceLanguage.ENGLISH, source)
            assertEquals("アニメーション", submitted)
        }
    }

    @Test
    fun originalSearchWorksWithoutDownloadingModels() {
        var originalSelected = false
        var translated = false
        compose.setContent {
            MaterialTheme {
                SearchTranslationDialog(
                    original = "动画", onDismiss = {},
                    onSearchOriginal = { originalSelected = true }, onSearchJapanese = {},
                    translator = KeywordTranslator { _, _, _ -> translated = true; "アニメ" },
                )
            }
        }
        compose.onNodeWithText(text(R.string.search_translate_search_original)).performClick()
        compose.runOnIdle {
            assertTrue(originalSelected)
            assertFalse(translated)
        }
    }
    @Test fun combinedSearchUsesEditedTranslationOnlyAfterConfirmation() {
        var combined: String? = null
        compose.setContent { MaterialTheme {
            SearchTranslationDialog("animation", {}, {}, {}, KeywordTranslator { _, _, ready -> ready(); "アニメ" },
                onSearchBoth = { combined = it })
        } }
        compose.onNodeWithText(text(R.string.search_translate_start)).performScrollTo().performClick()
        compose.onNodeWithText("アニメ").performScrollTo().performTextReplacement("アニメーション")
        compose.runOnIdle { assertNull(combined) }
        compose.onNodeWithText(text(R.string.search_both)).performScrollTo().performClick()
        compose.waitUntil(5000) { combined != null }
        compose.runOnIdle { assertEquals("アニメーション", combined) }
    }

    @Test fun translatedTagSuggestionUsesCanonicalSiteKeyWithoutTranslation() {
        var key: String? = null
        compose.setContent { MaterialTheme {
            SearchTranslationDialog("Uncensored", {}, {}, {}, KeywordTranslator { _, _, _ -> error("Must not translate") },
                onSearchTag = { key = it.searchKey })
        } }
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.search_matching_tag, "Uncensored")
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("無碼", key) }
    }

}
