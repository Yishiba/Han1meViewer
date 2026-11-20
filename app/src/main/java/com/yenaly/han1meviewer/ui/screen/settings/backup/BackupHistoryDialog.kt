package com.yenaly.han1meviewer.ui.screen.settings.backup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.backup.*
import java.text.DateFormat
import java.util.Date

private fun date(value: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(value))

@Composable
fun BackupHistoryDialog(
    state: BackupUiState, busy: Boolean, message: Int?, onDismiss: () -> Unit,
    onConfigure: (BackupSettings) -> Unit, onCreate: () -> Unit, onChooseDirectory: () -> Unit,
    onRestore: (BackupVersion) -> Unit, onExport: (BackupVersion) -> Unit, onDelete: (BackupVersion) -> Unit,
    onConfigureEncryption: () -> Unit = {}, onDisableEncryption: () -> Unit = {},
) {
    var restore by remember { mutableStateOf<BackupVersion?>(null) }
    var delete by remember { mutableStateOf<BackupVersion?>(null) }
    val enabled = state.loaded && !busy
    val automaticLabel = stringResource(R.string.backup_automatic)
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.9f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_history_title), style = MaterialTheme.typography.titleLarge)
                if (busy || !state.loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("backup.message")) }
                LazyColumn(Modifier.weight(1f).testTag("backup.list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Text(stringResource(R.string.backup_scope), style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.backup_automatic))
                            Switch(state.settings.enabled, onCheckedChange = { onConfigure(state.settings.copy(enabled = it)) },
                                enabled = enabled, modifier = Modifier.testTag("backup.enabled")
                                    .semantics { contentDescription = automaticLabel })
                        }
                        Text(stringResource(R.string.backup_schedule_help), style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1 to R.string.backup_daily, 7 to R.string.backup_weekly).forEach { (days, label) ->
                                FilterChip(state.settings.days == days, { onConfigure(state.settings.copy(days = days)) },
                                    label = { Text(stringResource(label)) }, enabled = enabled,
                                    modifier = Modifier.testTag("backup.days.$days"))
                            }
                        }
                        Text(stringResource(R.string.backup_retention), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(3, 7, 14, 30).forEach { keep ->
                                FilterChip(state.settings.keep == keep, { onConfigure(state.settings.copy(keep = keep)) },
                                    label = { Text(stringResource(R.string.backup_keep_count, keep)) }, enabled = enabled,
                                    modifier = Modifier.testTag("backup.keep.$keep"))
                            }
                        }
                        Text(stringResource(R.string.backup_crypto_scope), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(if (state.settings.encryptedMirror) R.string.backup_crypto_copy_on else R.string.backup_crypto_copy_off))
                        FlowRow {
                            TextButton(onConfigureEncryption, enabled = enabled) { Text(stringResource(R.string.backup_crypto_set_password)) }
                            if (state.settings.encryptedMirror) TextButton(onDisableEncryption, enabled = enabled) { Text(stringResource(R.string.backup_crypto_disable)) }
                        }
                        Text(stringResource(R.string.backup_storage_help), style = MaterialTheme.typography.bodySmall)
                        state.settings.mirrorUri?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onChooseDirectory, enabled = enabled) { Text(stringResource(R.string.backup_choose_directory)) }
                            if (state.settings.mirrorUri != null) TextButton(
                                onClick = { onConfigure(state.settings.copy(mirrorUri = null)) }, enabled = enabled,
                            ) { Text(stringResource(R.string.backup_local_only)) }
                        }
                        if (state.settings.lastSuccess > 0) Text(stringResource(R.string.backup_last_success, date(state.settings.lastSuccess)),
                            style = MaterialTheme.typography.bodySmall)
                        if (state.settings.lastFailure) Text(stringResource(R.string.backup_last_failed), color = MaterialTheme.colorScheme.error)
                        if (state.settings.mirrorFailed) Text(stringResource(R.string.backup_mirror_failed), color = MaterialTheme.colorScheme.error)
                        Button(onCreate, enabled = enabled, modifier = Modifier.testTag("backup.create")) {
                            Text(stringResource(R.string.backup_now))
                        }
                    }
                    item {
                        HorizontalDivider()
                        Text(stringResource(R.string.backup_versions), style = MaterialTheme.typography.titleMedium)
                        if (state.loaded && state.versions.isEmpty()) Text(stringResource(R.string.backup_empty))
                    }
                    items(state.versions, key = { it.id }) { version ->
                        ElevatedCard(Modifier.fillMaxWidth().testTag("backup.version.${version.id}")) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(date(version.createdAt), style = MaterialTheme.typography.titleSmall)
                                val kind = when (version.kind) {
                                    BackupKind.MANUAL -> R.string.backup_kind_manual
                                    BackupKind.AUTOMATIC -> R.string.backup_kind_automatic
                                    BackupKind.BEFORE_RESTORE -> R.string.backup_kind_safety
                                }
                                Text(stringResource(kind), style = MaterialTheme.typography.labelMedium)
                                Text(stringResource(R.string.backup_version_summary, version.downloads, version.histories,
                                    version.rules, (version.bytes + 1023) / 1024), style = MaterialTheme.typography.bodySmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton({ restore = version }, enabled = enabled,
                                        modifier = Modifier.testTag("backup.restore.${version.id}")) { Text(stringResource(R.string.backup_restore_version)) }
                                    TextButton({ onExport(version) }, enabled = enabled,
                                        modifier = Modifier.testTag("backup.export.${version.id}")) { Text(stringResource(R.string.backup_export_version)) }
                                    if (version.kind != BackupKind.BEFORE_RESTORE) TextButton({ delete = version }, enabled = enabled,
                                        modifier = Modifier.testTag("backup.delete.${version.id}")) { Text(stringResource(R.string.delete)) }
                                }
                            }
                        }
                    }
                }
                TextButton(onDismiss, enabled = !busy, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
    restore?.let { version -> AlertDialog(
        onDismissRequest = { restore = null }, title = { Text(stringResource(R.string.backup_restore_version)) },
        text = { Text(stringResource(R.string.backup_restore_confirm, date(version.createdAt))) },
        confirmButton = { TextButton({ restore = null; onRestore(version) }, modifier = Modifier.testTag("backup.confirm.restore")) {
            Text(stringResource(R.string.confirm))
        } }, dismissButton = { TextButton({ restore = null }) { Text(stringResource(R.string.cancel)) } },
    ) }
    delete?.let { version -> AlertDialog(
        onDismissRequest = { delete = null }, title = { Text(stringResource(R.string.delete)) },
        text = { Text(stringResource(R.string.backup_delete_confirm, date(version.createdAt))) },
        confirmButton = { TextButton({ delete = null; onDelete(version) }, modifier = Modifier.testTag("backup.confirm.delete")) {
            Text(stringResource(R.string.delete))
        } }, dismissButton = { TextButton({ delete = null }) { Text(stringResource(R.string.cancel)) } },
    ) }
}
