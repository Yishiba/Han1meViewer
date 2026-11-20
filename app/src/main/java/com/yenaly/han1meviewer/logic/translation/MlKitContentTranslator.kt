package com.yenaly.han1meviewer.logic.translation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import java.io.IOException

class WifiModelRequired : IllegalStateException()

object MlKitContentTranslator {
    fun create(context: Context): AutomaticTextTranslation {
        val app = context.applicationContext
        val cache = ChineseTranslationCache(app)
        return AutomaticTextTranslation(TranslateLanguage.getAllLanguages().toSet(), ::identify,
            { text, source, target, mobile, ready -> translate(app, text, source, target, mobile, ready) }, cache::get, cache::put)
    }

    private suspend fun identify(text: String): String? {
        val identifier = LanguageIdentification.getClient()
        return try { TranslateLanguage.fromLanguageTag(bounded(10_000) { identifier.identifyLanguage(text).awaitResult() }) }
        finally { identifier.close() }
    }

    private suspend fun translate(context: Context, text: String, source: String, target: String,
                                  mobile: Boolean, ready: () -> Unit): String = TranslationModels.mutex.withLock {
        coroutineContext.ensureActive()
        val translator = Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(source).setTargetLanguage(target).build())
        try {
            val installed = TranslationModels.installed()
            val needed = setOf(source, target).filterNot { it == "en" }
            if (!mobile && !installed.containsAll(needed)) {
                val manager = context.getSystemService(ConnectivityManager::class.java)
                val network = manager.getNetworkCapabilities(manager.activeNetwork)
                if (network?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) throw WifiModelRequired()
            }
            val conditions = DownloadConditions.Builder().apply { if (!mobile) requireWifi() }.build()
            bounded(120_000) { translator.downloadModelIfNeeded(conditions).awaitResult() }
            coroutineContext.ensureActive()
            ready()
            bounded(30_000) { translator.translate(text).awaitResult() }
        } finally { translator.close() }
    }

    private suspend fun <T> bounded(timeout: Long, action: suspend () -> T): T = try {
        withTimeout(timeout) { action() }
    } catch (cancelled: CancellationException) {
        // A timeout/task cancellation is retryable; caller cancellation still propagates normally.
        coroutineContext.ensureActive()
        throw IOException("Translation operation interrupted", cancelled)
    }
}
