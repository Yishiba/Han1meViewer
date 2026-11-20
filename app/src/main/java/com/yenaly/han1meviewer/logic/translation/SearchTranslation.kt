package com.yenaly.han1meviewer.logic.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SourceLanguage { CHINESE, ENGLISH }
enum class TranslationPhase { IDLE, DOWNLOADING, TRANSLATING, READY, ERROR }

data class SearchTranslationState(
    val source: SourceLanguage = SourceLanguage.CHINESE,
    val phase: TranslationPhase = TranslationPhase.IDLE,
    val translation: String = "",
) {
    val busy: Boolean get() = phase == TranslationPhase.DOWNLOADING || phase == TranslationPhase.TRANSLATING
}

fun interface KeywordTranslator {
    suspend fun translate(text: String, source: SourceLanguage, onModelReady: () -> Unit): String
}

/** A dialog owns one session. Neither translation nor editing changes the original query. */
class SearchTranslation(
    val original: String,
    private val scope: CoroutineScope,
    private val translator: KeywordTranslator,
) {
    private val mutableState = MutableStateFlow(SearchTranslationState(source = SearchTerms.detect(original) ?: SourceLanguage.CHINESE))
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun selectLanguage(source: SourceLanguage) {
        if (source == state.value.source) return
        cancel()
        mutableState.value = SearchTranslationState(source = source)
    }

    fun useTranslation(text: String) {
        cancel()
        mutableState.value = state.value.copy(phase = TranslationPhase.READY, translation = text)
    }

    fun editTranslation(text: String) {
        if (state.value.phase == TranslationPhase.READY) {
            mutableState.value = state.value.copy(translation = text)
        }
    }

    fun translate() {
        if (original.isBlank() || state.value.busy) return
        val source = state.value.source
        mutableState.value = SearchTranslationState(source, TranslationPhase.DOWNLOADING)
        job = scope.launch {
            try {
                val translated = translator.translate(original.trim(), source) {
                    coroutineContext.ensureActive()
                    mutableState.value = state.value.copy(phase = TranslationPhase.TRANSLATING)
                }
                coroutineContext.ensureActive()
                check(translated.isNotBlank()) { "Empty translation" }
                mutableState.value = state.value.copy(phase = TranslationPhase.READY, translation = translated)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(phase = TranslationPhase.ERROR)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
