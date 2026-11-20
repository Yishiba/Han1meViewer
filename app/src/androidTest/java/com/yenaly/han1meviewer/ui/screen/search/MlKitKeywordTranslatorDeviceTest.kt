package com.yenaly.han1meviewer.ui.screen.search

import android.app.DownloadManager
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.yenaly.han1meviewer.logic.translation.MlKitKeywordTranslator
import com.yenaly.han1meviewer.logic.translation.SourceLanguage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

/** Integration smoke test: first run requires access to Google's model download servers. */
@LargeTest
class MlKitKeywordTranslatorDeviceTest {
    @Test
    fun realModelsTranslateChineseAndEnglishToJapanese() = runBlocking {
        var stage = "starting"
        try {
            withTimeout(120_000) {
                val translator = MlKitKeywordTranslator()
                for ((source, text) in listOf(SourceLanguage.CHINESE to "你好", SourceLanguage.ENGLISH to "hello")) {
                    var modelReady = false
                    stage = "$source: preparing models"
                    val translated = translator.translate(text, source) {
                        modelReady = true
                        stage = "$source: translating"
                    }
                    assertTrue("Model must be ready before translation", modelReady)
                    assertTrue("Expected Japanese output for $source", translated.any { it in '\u3040'..'\u30ff' })
                }
            }
        } catch (failure: Exception) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val manager = context.getSystemService(DownloadManager::class.java)
            val downloads = buildList {
                manager.query(DownloadManager.Query()).use { cursor ->
                    while (cursor.moveToNext()) {
                        fun value(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
                        add("id=${value(DownloadManager.COLUMN_ID)}, status=${value(DownloadManager.COLUMN_STATUS)}, " +
                            "reason=${value(DownloadManager.COLUMN_REASON)}, bytes=${value(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)}/${value(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)}")
                    }
                }
            }
            throw AssertionError("ML Kit failed at $stage; downloads=$downloads", failure)
        }
    }
}
