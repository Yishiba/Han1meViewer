package com.yenaly.han1meviewer.ui.screen.search

import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import com.yenaly.han1meviewer.logic.translation.*
import com.yenaly.han1meviewer.logic.backup.*
import com.yenaly.han1meviewer.ui.navigation.settings.SettingsPreferenceKeys as K
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AutomaticTranslationDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun settingsPersistAndMobileConsentIsExcludedFromBackup() = runBlocking {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val device = context.getSharedPreferences("auto_translation_device", 0)
        val oldDevice = device.getBoolean(K.AUTO_TRANSLATE_MOBILE_MODELS, false)
        val hadDevice = device.contains(K.AUTO_TRANSLATE_MOBILE_MODELS)
        val keys = setOf(K.AUTO_TRANSLATE_ENABLED, K.AUTO_TRANSLATE_TARGET, K.AUTO_TRANSLATE_MOBILE_MODELS)
        val old = prefs.all.filterKeys { it in keys }
        try {
            prefs.edit().apply { keys.forEach { remove(it) } }.commit()
            device.edit().remove(K.AUTO_TRANSLATE_MOBILE_MODELS).commit()
            assertEquals(AutoTranslationConfig(), AutoTranslationPreferences(context).read())
            AutoTranslationPreferences(context).save(AutoTranslationConfig(true, "ko", true))
            assertEquals(AutoTranslationConfig(true, "ko", true), AutoTranslationPreferences(context).read())
            val payload = BackupPayload(settings = mapOf(
                K.AUTO_TRANSLATE_ENABLED to PreferenceValue.BooleanValue(true),
                K.AUTO_TRANSLATE_TARGET to PreferenceValue.StringValue("ko"),
                K.AUTO_TRANSLATE_MOBILE_MODELS to PreferenceValue.BooleanValue(true)))
            val restored = BackupCodec.decode(BackupCodec.encode(payload))
            assertEquals(PreferenceValue.StringValue("ko"), restored.settings?.get(K.AUTO_TRANSLATE_TARGET))
            assertEquals(PreferenceValue.BooleanValue(true), restored.settings?.get(K.AUTO_TRANSLATE_ENABLED))
            assertFalse(restored.settings!!.containsKey(K.AUTO_TRANSLATE_MOBILE_MODELS))
        } finally {
            val edit = prefs.edit()
            keys.forEach(edit::remove)
            old.forEach { (key, value) -> when (value) { is Boolean -> edit.putBoolean(key, value); is String -> edit.putString(key, value) } }
            edit.commit()
            device.edit().apply { if (hadDevice) putBoolean(K.AUTO_TRANSLATE_MOBILE_MODELS, oldDevice)
                else remove(K.AUTO_TRANSLATE_MOBILE_MODELS) }.commit()
        }
    }

    @Test fun realLanguageDetectionAndTranslationWorkForMultipleTargets() = runBlocking {
        withTimeout(180_000) {
            val translator = MlKitContentTranslator.create(context)
            val japanese = "今日は天気がいいので、公園へ散歩に行きます。"
            val chinese = translator.translate(japanese, "zh", true)
            assertFalse(chinese.isNullOrBlank())
            assertTrue(chinese!!.any { it in '\u3400'..'\u9fff' })
            val english = translator.translate(japanese, "en", true)
            assertTrue(english.orEmpty().any { it in 'a'..'z' || it in 'A'..'Z' })
            val alreadyEnglish = "The weather is beautiful today, so I am going for a walk in the park."
            assertNull(translator.translate(alreadyEnglish, "en", true))
        }
    }
}
