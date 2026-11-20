package com.yenaly.han1meviewer.ui.screen.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.google.mlkit.nl.translate.TranslateLanguage
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.translation.*
import kotlinx.coroutines.*

@Composable
fun AutoTranslationSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { AutoTranslationPreferences(context) }
    val initial = remember(store) { store.read() }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var target by remember { mutableStateOf(initial.target) }
    var mobile by remember { mutableStateOf(initial.mobileModels) }
    var choosing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (choosing) {
        val codes = remember { listOf("zh", "en", "ja", "ko") + TranslateLanguage.getAllLanguages()
            .filterNot { it in setOf("zh", "en", "ja", "ko") }.sortedBy(::translationLanguageName) }
        AlertDialog(onDismissRequest = { choosing = false }, title = { Text(stringResource(R.string.auto_translation_target)) },
            text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                codes.forEach { code ->
                    TextButton(onClick = { target = code; choosing = false }, modifier = Modifier.fillMaxWidth()) {
                        Text(translationLanguageName(code) + " ($code)" + if (code == target) " ✓" else "")
                    }
                }
            } }, confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.cancel)) } })
    }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(stringResource(R.string.auto_translation_title)) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToggleTranslationSetting(stringResource(R.string.auto_translation_enable), enabled, !saving) { enabled = it }
            Text(stringResource(R.string.auto_translation_help), style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !saving, onClick = { choosing = true }) {
                Text(stringResource(R.string.auto_translation_target) + ": " + translationLanguageName(target))
            }
            ToggleTranslationSetting(stringResource(R.string.auto_translation_mobile), mobile, !saving) { mobile = it }
            Text(stringResource(R.string.auto_translation_download_help), style = MaterialTheme.typography.bodySmall)
            if (failed) Text(stringResource(R.string.discovery_operation_failed), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(enabled = !saving, onClick = {
            saving = true
            scope.launch {
                try { withContext(Dispatchers.IO) { store.save(AutoTranslationConfig(enabled, target, mobile)) }; onDismiss() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
                finally { saving = false }
            }
        }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun ToggleTranslationSetting(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled, Role.Switch) { onChange(it) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
