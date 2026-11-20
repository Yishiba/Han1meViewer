package com.yenaly.han1meviewer.ui.screen.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.translation.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ContentTranslationButton(original: String, active: Boolean = true, maxLines: Int = Int.MAX_VALUE) {
    if (original.isBlank()) return
    var open by rememberSaveable(original) { mutableStateOf(false) }
    val config = rememberAutoTranslationConfig()
    AutomaticTranslatedText(original, active, maxLines, config)
    TextButton(onClick = { open = true }) {
        Text(if (config.target == "zh") stringResource(R.string.discovery_translate_chinese)
            else stringResource(R.string.auto_translation_manual, translationLanguageName(config.target)))
    }
    if (open) ContentTranslationDialog(original, { open = false }, targetLanguage = config.target)
}

@Composable
fun ContentTranslationDialog(
    original: String,
    onDismiss: () -> Unit,
    translation: ChineseTextTranslation? = null,
    targetLanguage: String = "zh",
    engine: AutomaticTextTranslation? = null,
) {
    val context = LocalContext.current
    val translator = remember(context, engine) { engine ?: MlKitContentTranslator.create(context) }
    val scope = rememberCoroutineScope()
    var source by rememberSaveable(original) { mutableStateOf(TextSource.JAPANESE) }
    var result by remember(original, source, targetLanguage) { mutableStateOf("") }
    var phase by remember(original, source, targetLanguage) { mutableStateOf(TranslationPhase.IDLE) }
    var sameLanguage by remember(original, source, targetLanguage) { mutableStateOf(false) }
    val busy = phase == TranslationPhase.DOWNLOADING || phase == TranslationPhase.TRANSLATING
    val canTranslate = original.length <= 5000

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (targetLanguage == "zh") stringResource(R.string.discovery_translate_chinese)
            else stringResource(R.string.auto_translation_manual, translationLanguageName(targetLanguage))) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.discovery_translation_hint), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = source == TextSource.JAPANESE, enabled = !busy,
                        onClick = { source = TextSource.JAPANESE }, label = { Text(stringResource(R.string.discovery_japanese)) })
                    FilterChip(selected = source == TextSource.ENGLISH, enabled = !busy,
                        onClick = { source = TextSource.ENGLISH }, label = { Text(stringResource(R.string.search_translate_english)) })
                    if (targetLanguage != "zh") FilterChip(selected = source == TextSource.CHINESE, enabled = !busy,
                        onClick = { source = TextSource.CHINESE }, label = { Text(stringResource(R.string.auto_translation_chinese)) })
                }
                Text(stringResource(R.string.discovery_original), style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Text(original) }
                if (result.isNotBlank()) {
                    HorizontalDivider()
                    Text(stringResource(R.string.discovery_machine_translation), style = MaterialTheme.typography.labelMedium)
                    SelectionContainer { Text(result) }
                }
                if (!canTranslate) Text(stringResource(R.string.discovery_text_too_long), color = MaterialTheme.colorScheme.error)
                if (sameLanguage) Text(stringResource(R.string.auto_translation_same_language))
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(if (phase == TranslationPhase.DOWNLOADING) R.string.discovery_downloading else R.string.discovery_translating))
                }
                if (phase == TranslationPhase.ERROR) Text(stringResource(R.string.discovery_translation_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && canTranslate, onClick = {
                phase = TranslationPhase.DOWNLOADING
                scope.launch {
                    try {
                        val output = withContext(Dispatchers.IO) {
                            if (translation != null) translation.translate(original, source) { phase = TranslationPhase.TRANSLATING }
                            else translator.translate(original, targetLanguage, mobileModels = true, source = source.code) {
                                phase = TranslationPhase.TRANSLATING
                            }
                        }
                        result = output.orEmpty()
                        sameLanguage = output == null
                        phase = TranslationPhase.READY
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { phase = TranslationPhase.ERROR }
                }
            }) { Text(stringResource(R.string.search_translate_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
