package com.yenaly.han1meviewer.ui.screen.search

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.translation.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import androidx.preference.PreferenceManager
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import com.yenaly.han1meviewer.logic.model.HanimeInfo
import com.yenaly.han1meviewer.logic.state.PageLoadingState
import com.yenaly.han1meviewer.ui.navigation.settings.SettingsPreferenceKeys as K

class AutomaticTranslationUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)
    private fun engine(action: suspend (String, String) -> String) = AutomaticTextTranslation(setOf("ja", "zh", "en", "ko"),
        { "ja" }, { original, _, target, _, ready -> ready(); action(original, target) }, { null }, { _, _ -> })

    private fun withSettings(action: () -> Unit) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val device = context.getSharedPreferences("auto_translation_device", 0)
        val oldDevice = device.getBoolean(K.AUTO_TRANSLATE_MOBILE_MODELS, false)
        val hadDevice = device.contains(K.AUTO_TRANSLATE_MOBILE_MODELS)
        val keys = setOf(K.AUTO_TRANSLATE_ENABLED, K.AUTO_TRANSLATE_TARGET, K.AUTO_TRANSLATE_MOBILE_MODELS)
        val old = prefs.all.filterKeys { it in keys }
        try { action() } finally {
            val edit = prefs.edit()
            keys.forEach(edit::remove)
            old.forEach { (key, value) -> when (value) { is Boolean -> edit.putBoolean(key, value); is String -> edit.putString(key, value) } }
            edit.commit()
            device.edit().apply { if (hadDevice) putBoolean(K.AUTO_TRANSLATE_MOBILE_MODELS, oldDevice)
                else remove(K.AUTO_TRANSLATE_MOBILE_MODELS) }.commit()
        }
    }

    @Test fun settingsSwitchAndTargetSelectionPersistOnlyOnConfirmation() = withSettings {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putBoolean(K.AUTO_TRANSLATE_ENABLED, false).putString(K.AUTO_TRANSLATE_TARGET, "zh")
            .putBoolean(K.AUTO_TRANSLATE_MOBILE_MODELS, false).commit()
        compose.setContent { MaterialTheme { AutoTranslationSettingsDialog {} } }
        compose.onNodeWithText(text(R.string.auto_translation_enable)).performClick()
        assertFalse(AutoTranslationPreferences(context).read().enabled)
        compose.onNodeWithText(text(R.string.auto_translation_target), substring = true).performClick()
        compose.onNodeWithText(translationLanguageName("en") + " (en)").performClick()
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        compose.waitUntil(5000) { AutoTranslationPreferences(context).read().enabled }
        assertEquals("en", AutoTranslationPreferences(context).read().target)
        assertFalse(AutoTranslationPreferences(context).read().mobileModels)
    }

    @Test fun actualSearchGridTranslatesVisibleTitlesAndNewItemsAfterScrolling() = withSettings {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(K.AUTO_TRANSLATE_ENABLED, true).putString(K.AUTO_TRANSLATE_TARGET, "zh").commit()
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val backend = engine { original, _ -> requests += original; "译文:$original" }
        val videos = (0..23).map { HanimeInfo("Title$it", "", it.toString(), itemType = HanimeInfo.NORMAL) }
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAutomaticTranslationEngine provides backend) {
                SearchResultsGrid(videos, PageLoadingState.Success(videos), false, {}, { _, _ -> }, {}, false, rememberLazyGridState())
            }
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("译文:Title0").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(requests.size < videos.size)
        compose.onNodeWithText("Title0").assertExists()
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            java.io.File(context.cacheDir, "automatic-grid.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(20)
        compose.waitUntil(5000) { requests.contains("Title20") }
    }

    @Test fun automaticallyTranslatesWithoutClickAndOriginalRemainsVisible() {
        val calls = AtomicInteger()
        val backend = engine { _, _ -> calls.incrementAndGet(); "示例中文译文" }
        compose.setContent { MaterialTheme { Column {
            Text("Original sample title")
            AutomaticTranslatedText("Original sample title", config = AutoTranslationConfig(true), engine = backend)
        } } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("示例中文译文").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Original sample title").assertExists()
        compose.runOnIdle { assertEquals(1, calls.get()) }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            java.io.File(context.cacheDir, "automatic-translation.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun disablingCancelsInFlightWorkAndRemovesTranslation() {
        val started = AtomicBoolean()
        val cancelled = AtomicBoolean()
        val enabled = mutableStateOf(true)
        val backend = engine { _, _ -> started.set(true); try { awaitCancellation() } finally { cancelled.set(true) } }
        compose.setContent { MaterialTheme { Column {
            Text("Original")
            AutomaticTranslatedText("Original", config = AutoTranslationConfig(enabled.value), engine = backend)
        } } }
        compose.waitUntil(5000) { started.get() }
        compose.runOnIdle { enabled.value = false }
        compose.waitUntil(5000) { cancelled.get() }
        compose.onNodeWithText("Original").assertExists()
        compose.onNodeWithText(text(R.string.auto_translation_working)).assertDoesNotExist()
    }

    @Test fun switchingTargetCannotPublishTheCancelledLanguage() {
        val target = mutableStateOf("zh")
        val entered = AtomicBoolean()
        val finish = CompletableDeferred<Unit>()
        val backend = engine { _, language ->
            if (language == "zh") withContext(NonCancellable) { entered.set(true); finish.await() }
            "result-$language"
        }
        compose.setContent { MaterialTheme { AutomaticTranslatedText("Original", config = AutoTranslationConfig(true, target.value), engine = backend) } }
        compose.waitUntil(5000) { entered.get() }
        compose.runOnIdle { target.value = "en" }
        finish.complete(Unit)
        compose.waitUntil(5000) { compose.onAllNodesWithText("result-en").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("result-zh").assertDoesNotExist()
    }

    @Test fun offscreenItemsDoNotStartUntilVisibleAndCancelWhenHidden() {
        val visible = mutableStateOf(false)
        val calls = AtomicInteger()
        val cancelled = AtomicBoolean()
        val backend = engine { _, _ -> calls.incrementAndGet(); try { awaitCancellation() } finally { cancelled.set(true) } }
        compose.setContent { MaterialTheme { AutomaticTranslatedText("Original", active = visible.value,
            config = AutoTranslationConfig(true), engine = backend) } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, calls.get()); visible.value = true }
        compose.waitUntil(5000) { calls.get() == 1 }
        compose.runOnIdle { visible.value = false }
        compose.waitUntil(5000) { cancelled.get() }
    }

    @Test fun failedAutomaticTranslationCanRetryWithoutLosingOriginal() {
        val calls = AtomicInteger()
        val backend = engine { _, _ -> if (calls.incrementAndGet() == 1) error("failed") else "recovered" }
        compose.setContent { MaterialTheme { Column {
            Text("Original")
            AutomaticTranslatedText("Original", config = AutoTranslationConfig(true), engine = backend)
        } } }
        compose.waitUntil(5000) { compose.onAllNodesWithText(text(R.string.auto_translation_retry)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.auto_translation_retry)).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("recovered").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Original").assertExists()
    }

    @Test fun manualTranslationHonorsOtherTargetLanguages() {
        val backend = engine { _, target -> "manual-$target" }
        compose.setContent { MaterialTheme { ContentTranslationDialog("Manual sample text", {}, targetLanguage = "en", engine = backend) } }
        compose.onNodeWithText(text(R.string.auto_translation_chinese)).performClick()
        compose.onNodeWithText(text(R.string.search_translate_start)).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("manual-en").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Manual sample text").assertExists()
    }
}
