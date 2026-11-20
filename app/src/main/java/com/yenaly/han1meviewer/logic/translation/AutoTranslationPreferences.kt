package com.yenaly.han1meviewer.logic.translation

import android.content.Context
import androidx.preference.PreferenceManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.yenaly.han1meviewer.logic.backup.BackupService
import com.yenaly.han1meviewer.ui.navigation.settings.SettingsPreferenceKeys as K
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class AutoTranslationPreferences(private val context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private val device = context.getSharedPreferences("auto_translation_device", Context.MODE_PRIVATE)
    fun read(): AutoTranslationConfig = AutoTranslationConfig(
        enabled = runCatching { prefs.getBoolean(K.AUTO_TRANSLATE_ENABLED, false) }.getOrDefault(false),
        target = runCatching { prefs.getString(K.AUTO_TRANSLATE_TARGET, "zh") }.getOrNull()
            ?.takeIf { it in TranslateLanguage.getAllLanguages() } ?: "zh",
        mobileModels = runCatching { device.getBoolean(K.AUTO_TRANSLATE_MOBILE_MODELS, false) }.getOrDefault(false),
    )
    fun observe() = callbackFlow {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in setOf(K.AUTO_TRANSLATE_ENABLED, K.AUTO_TRANSLATE_TARGET, K.AUTO_TRANSLATE_MOBILE_MODELS)) trySend(read())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        device.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            device.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.distinctUntilChanged()

    suspend fun save(config: AutoTranslationConfig) = BackupService.get(context).withSearchToolsAccess {
        require(config.target in TranslateLanguage.getAllLanguages())
        check(device.edit().putBoolean(K.AUTO_TRANSLATE_MOBILE_MODELS, config.mobileModels).commit())
        check(prefs.edit().putBoolean(K.AUTO_TRANSLATE_ENABLED, config.enabled)
            .putString(K.AUTO_TRANSLATE_TARGET, config.target).commit())
    }
}
