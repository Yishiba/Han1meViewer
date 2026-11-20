package com.yenaly.han1meviewer.logic.translation

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object TranslationModels {
    val mutex = kotlinx.coroutines.sync.Mutex()
    private val manager get() = com.google.mlkit.common.model.RemoteModelManager.getInstance()
    private fun model(code: String) = com.google.mlkit.nl.translate.TranslateRemoteModel.Builder(code).build()
    suspend fun installed(): Set<String> = manager.getDownloadedModels(com.google.mlkit.nl.translate.TranslateRemoteModel::class.java).awaitResult().map { it.language }.toSet()
    suspend fun download(code: String) = mutex.withLock { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { manager.download(model(code), DownloadConditions.Builder().build()).awaitResult() } }
    suspend fun delete(code: String) = mutex.withLock { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { manager.deleteDownloadedModel(model(code)).awaitResult() } }
}

class MlKitKeywordTranslator : KeywordTranslator {
    override suspend fun translate(
        text: String,
        source: SourceLanguage,
        onModelReady: () -> Unit,
    ): String = TranslationModels.mutex.withLock {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(when (source) {
                SourceLanguage.CHINESE -> TranslateLanguage.CHINESE
                SourceLanguage.ENGLISH -> TranslateLanguage.ENGLISH
            })
            .setTargetLanguage(TranslateLanguage.JAPANESE)
            .build()
        val translator = Translation.getClient(options)
        try {
            // Explicit user action permits downloading over the current connection.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitResult() }
            coroutineContext.ensureActive()
            onModelReady()
            translator.translate(text).awaitResult()
        } finally {
            translator.close()
        }
    }
}

internal suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
