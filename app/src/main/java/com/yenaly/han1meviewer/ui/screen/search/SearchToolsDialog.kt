package com.yenaly.han1meviewer.ui.screen.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.translation.*
import kotlinx.coroutines.*

@Composable
fun SearchToolsDialog(store: SearchToolsStore, original: String, onUse: (String) -> Unit, onDismiss: () -> Unit) {
    var data by remember { mutableStateOf(SearchToolsData()) }
    var installed by remember { mutableStateOf(emptySet<String>()) }
    var alias by remember { mutableStateOf(original) }
    var target by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val autoConfig = rememberAutoTranslationConfig()
    fun perform(action: suspend () -> Unit) {
        busy = true; failed = false
        scope.launch {
            try { withContext(Dispatchers.IO) { action() }; data = withContext(Dispatchers.IO) { store.read() }; installed = TranslationModels.installed() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { perform { } }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(R.string.search_tools)) }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.search_tools_help))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failed) Text(stringResource(R.string.search_tools_error), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.search_aliases), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(alias, { alias = it }, label = { Text(stringResource(R.string.search_alias_original)) }, enabled = !busy)
            OutlinedTextField(target, { target = it }, label = { Text(stringResource(R.string.search_translate_result)) }, enabled = !busy)
            TextButton({ perform { store.saveAlias(alias, target) } }, enabled = !busy && alias.isNotBlank() && target.isNotBlank()) { Text(stringResource(R.string.search_alias_save)) }
            data.aliases.forEach { entry ->
                Text("${entry.original} → ${entry.translated}")
                Row {
                    TextButton({ onUse(entry.translated) }, enabled = !busy && SearchTerms.normalize(entry.original) == SearchTerms.normalize(original)) { Text(stringResource(R.string.search_reuse)) }
                    TextButton({ perform { store.remove(entry, true) } }, enabled = !busy) { Text(stringResource(R.string.delete)) }
                }
            }
            Text(stringResource(R.string.search_translation_history), style = MaterialTheme.typography.titleMedium)
            TextButton({ perform { store.clearHistory() } }, enabled = !busy) { Text(stringResource(R.string.search_clear_history)) }
            data.history.forEach { entry ->
                Text("${entry.original} → ${entry.translated}")
                Row {
                    TextButton({ onUse(entry.translated) }, enabled = !busy && entry.original == original) { Text(stringResource(R.string.search_reuse)) }
                    TextButton({ perform { store.remove(entry, false) } }, enabled = !busy) { Text(stringResource(R.string.delete)) }
                }
            }
            Text(stringResource(R.string.search_models), style = MaterialTheme.typography.titleMedium)
            TextButton(enabled = !busy, onClick = { perform { ChineseTranslationCache(context).clear() } }) {
                Text(stringResource(R.string.discovery_clear_cache))
            }
            Text(stringResource(R.string.search_models_help))
            (listOf("zh", "en", "ja", autoConfig.target) + installed.sorted()).distinct().forEach { code ->
                Row {
                    Text("$code · " + stringResource(if (code in installed) R.string.search_model_installed else R.string.search_model_absent))
                    TextButton({ perform { if (code in installed) TranslationModels.delete(code) else TranslationModels.download(code) } }, enabled = !busy) {
                        Text(stringResource(if (code in installed) R.string.delete else R.string.search_model_download))
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
}
