package com.yenaly.han1meviewer.ui.screen.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.translation.*
import kotlinx.coroutines.*
import java.util.Locale

val LocalAutomaticTranslationEngine = staticCompositionLocalOf<AutomaticTextTranslation?> { null }

@Composable
fun rememberAutoTranslationConfig(): AutoTranslationConfig {
    val context = LocalContext.current
    val store = remember(context) { AutoTranslationPreferences(context) }
    val flow = remember(store) { store.observe() }
    val config by flow.collectAsStateWithLifecycle(initialValue = store.read())
    return config
}

fun translationLanguageName(code: String): String = Locale.forLanguageTag(code)
    .getDisplayName(Locale.getDefault()).ifBlank { code }

private data class AutomaticTextState(val phase: TranslationPhase = TranslationPhase.IDLE,
                                      val text: String = "", val wifi: Boolean = false)

@Composable
fun AutomaticTranslatedText(
    original: String,
    active: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    config: AutoTranslationConfig? = null,
    engine: AutomaticTextTranslation? = null,
) {
    val settings = config ?: rememberAutoTranslationConfig()
    if (!settings.enabled || original.isBlank()) return
    val context = LocalContext.current
    val suppliedEngine = engine ?: LocalAutomaticTranslationEngine.current
    val translator = remember(context, suppliedEngine) { suppliedEngine ?: MlKitContentTranslator.create(context) }
    val owner = LocalLifecycleOwner.current
    var started by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ -> started = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val network by remember(context) { TranslationNetwork.observe(context) }.collectAsStateWithLifecycle()
    var state by remember(original, settings) { mutableStateOf(AutomaticTextState()) }
    var retry by remember(original, settings) { mutableIntStateOf(0) }
    LaunchedEffect(original, settings, active, started, network, retry) {
        if (!active || !started || original.length > 5000) return@LaunchedEffect
        // Let a fling settle before allocating a language identifier or joining the translation queue.
        delay(250)
        state = AutomaticTextState(TranslationPhase.DOWNLOADING)
        try {
            val result = withContext(Dispatchers.IO) {
                translator.translate(original, settings.target, settings.mobileModels) {
                    state = AutomaticTextState(TranslationPhase.TRANSLATING)
                }
            }
            ensureActive()
            state = AutomaticTextState(TranslationPhase.READY, result.orEmpty())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: WifiModelRequired) { state = AutomaticTextState(TranslationPhase.ERROR, wifi = true) }
        catch (_: Exception) { state = AutomaticTextState(TranslationPhase.ERROR) }
    }
    if (!active || !started) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            original.length > 5000 -> Text(stringResource(R.string.discovery_text_too_long), style = MaterialTheme.typography.bodySmall)
            state.phase == TranslationPhase.READY && state.text.isNotBlank() -> {
                Text(stringResource(R.string.auto_translation_result, translationLanguageName(settings.target)),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                SelectionContainer {
                    Text(state.text, style = MaterialTheme.typography.bodyMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
                }
            }
            state.phase == TranslationPhase.DOWNLOADING || state.phase == TranslationPhase.TRANSLATING ->
                Text(stringResource(R.string.auto_translation_working), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.phase == TranslationPhase.ERROR -> {
                Text(stringResource(if (state.wifi) R.string.auto_translation_wifi_wait else R.string.auto_translation_failed),
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { retry++ }) { Text(stringResource(R.string.auto_translation_retry)) }
            }
        }
    }
}
