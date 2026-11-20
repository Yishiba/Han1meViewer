package com.yenaly.han1meviewer.ui.screen.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.yenaly.han1meviewer.logic.translation.*
import com.yenaly.han1meviewer.logic.model.SearchOption
import com.yenaly.han1meviewer.util.loadAssetAs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.translation.MlKitKeywordTranslator
import com.yenaly.han1meviewer.logic.translation.KeywordTranslator
import com.yenaly.han1meviewer.logic.translation.SearchTranslation
import com.yenaly.han1meviewer.logic.translation.SourceLanguage
import com.yenaly.han1meviewer.logic.translation.TranslationPhase

@Composable
fun SearchTranslationDialog(
    original: String,
    onDismiss: () -> Unit,
    onSearchOriginal: () -> Unit,
    onSearchJapanese: (String) -> Unit,
    translator: KeywordTranslator = MlKitKeywordTranslator(),
    onSearchBoth: ((String) -> Unit)? = null,
    onSearchTag: ((SearchOption) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val store = remember { SearchToolsStore(context) }
    var tools by remember { mutableStateOf(false) }
    var historyError by remember { mutableStateOf(false) }
    val tags = remember(original) { loadAssetAs<Map<String, List<SearchOption>>>("search_options/tags.json").orEmpty().values.flatten().filter {
        SearchTerms.matches(original, listOfNotNull(it.name, it.searchKey, it.lang?.zhrCN, it.lang?.zhrTW, it.lang?.en, it.lang?.ja))
    }.distinctBy { it.searchKey }.take(5) }
    fun search(text: String, both: Boolean) {
        scope.launch {
            try { withContext(Dispatchers.IO) { store.remember(original, text) } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { historyError = true }
            if (both) onSearchBoth?.invoke(text) else onSearchJapanese(text)
        }
    }
    val session = remember(original, scope) { SearchTranslation(original, scope, translator) }
    val state by session.state.collectAsStateWithLifecycle()
    LaunchedEffect(original, session) {
        try {
            val alias = withContext(Dispatchers.IO) { store.read().aliases.firstOrNull { SearchTerms.normalize(it.original) == SearchTerms.normalize(original) } }
            if (alias != null && session.state.value.phase == TranslationPhase.IDLE) session.useTranslation(alias.translated)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { historyError = true }
    }
    val uriHandler = LocalUriHandler.current
    DisposableEffect(session) { onDispose { session.cancel() } }

    if (tools) SearchToolsDialog(store, original, onUse = { text ->
        session.useTranslation(text)
        tools = false
    }, onDismiss = { tools = false })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.search_translate_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.search_translate_original), style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(original) }
                TextButton({ tools = true }, enabled = !state.busy) { Text(stringResource(R.string.search_tools)) }
                TextButton({ session.selectLanguage(SearchTerms.detect(original) ?: state.source) }, enabled = !state.busy) {
                    Text(stringResource(if (SearchTerms.detect(original) == null) R.string.search_detect_unknown else R.string.search_detect))
                }
                tags.forEach { tag -> TextButton({ onSearchTag?.invoke(tag) }, enabled = onSearchTag != null && !state.busy) {
                    Text(stringResource(R.string.search_matching_tag, tag.value))
                } }
                Text(stringResource(R.string.search_translate_source), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceLanguage.entries.forEach { language ->
                        FilterChip(
                            selected = state.source == language,
                            onClick = { session.selectLanguage(language) },
                            label = { Text(stringResource(when (language) {
                                SourceLanguage.CHINESE -> R.string.search_translate_chinese
                                SourceLanguage.ENGLISH -> R.string.search_translate_english
                            })) },
                        )
                    }
                }
                Text(stringResource(R.string.search_translate_privacy), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = session::translate, enabled = !state.busy) {
                    Text(stringResource(R.string.search_translate_start))
                }
                if (state.busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(if (state.phase == TranslationPhase.DOWNLOADING)
                        R.string.search_translate_downloading else R.string.search_translate_working))
                }
                if (state.phase == TranslationPhase.ERROR) {
                    Text(stringResource(R.string.search_translate_error), color = MaterialTheme.colorScheme.error)
                }
                if (historyError) Text(stringResource(R.string.search_tools_error))
                if (state.phase == TranslationPhase.READY) {
                    if (onSearchBoth != null) TextButton({ search(state.translation.trim(), true) }, enabled = state.translation.isNotBlank()) {
                        Text(stringResource(R.string.search_both))
                    }
                    OutlinedTextField(
                        value = state.translation,
                        onValueChange = session::editTranslation,
                        label = { Text(stringResource(R.string.search_translate_result)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                    )
                    TextButton(onClick = { uriHandler.openUri("https://translate.google.com/") }) {
                        Text(stringResource(R.string.search_translate_attribution))
                    }
                    Text(stringResource(R.string.search_translate_accuracy), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = state.phase == TranslationPhase.READY && state.translation.isNotBlank(),
                onClick = { search(state.translation.trim(), false) },
            ) { Text(stringResource(R.string.search_translate_search_japanese)) }
        },
        dismissButton = {
            TextButton(onClick = onSearchOriginal) {
                Text(stringResource(R.string.search_translate_search_original))
            }
        },
    )
}
