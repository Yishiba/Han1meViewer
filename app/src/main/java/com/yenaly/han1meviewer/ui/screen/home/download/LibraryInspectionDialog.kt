package com.yenaly.han1meviewer.ui.screen.home.download

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.entity.download.HanimeDownloadEntity
import com.yenaly.han1meviewer.logic.library.*
import com.yenaly.han1meviewer.ui.component.ConfirmDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun LibraryInspectionDialog(
    state: InspectionState,
    onStart: (Long) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onPlay: (HanimeDownloadEntity) -> Unit,
    onDelete: (HanimeDownloadEntity) -> Unit,
    onRemoveMissing: suspend (HanimeDownloadEntity) -> Boolean,
) {
    var threshold by rememberSaveable { mutableStateOf("500") }
    val thresholdValue = threshold.toLongOrNull()?.takeIf { it in 1..MediaLibraryRule.MAX_SIZE_MIB }
    var pendingMissing by remember { mutableStateOf<HanimeDownloadEntity?>(null) }
    var removing by remember { mutableStateOf(false) }
    var removalFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val report = state.report
    val actionsEnabled = !state.stale && !state.running && !removing
    val sharedIds = report?.duplicates.orEmpty().filter { it.sameReference }.flatMap { it.files }.map { it.video.id }.toSet()
    LaunchedEffect(state.stale) { if (state.stale) pendingMissing = null }

    Dialog(onDismissRequest = { if (!removing) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth(.94f).fillMaxHeight(.9f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.library_inspect), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.library_inspect_help), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = threshold, onValueChange = { threshold = it }, singleLine = true,
                    enabled = !state.running && !removing, label = { Text(stringResource(R.string.library_large_threshold)) },
                    modifier = Modifier.fillMaxWidth().testTag("library.inspect.threshold"),
                    isError = thresholdValue == null,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                if (state.running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(if (state.progress.stage == InspectionStage.FILES) R.string.library_scan_files else R.string.library_scan_hashes,
                        state.progress.completed, state.progress.total))
                }
                if (state.failed || removalFailed) Text(stringResource(R.string.library_inspect_error), color = MaterialTheme.colorScheme.error)
                if (state.stale) Text(stringResource(R.string.library_inspect_stale), color = MaterialTheme.colorScheme.error)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (report == null && !state.running) item { Text(stringResource(R.string.library_inspect_start_help)) }
                    report?.let { result ->
                        item { Text(stringResource(R.string.library_scan_summary, result.source.size, result.issues.size), style = MaterialTheme.typography.titleSmall) }
                        itemsIndexed(result.issues, key = { _, file -> "issue-${file.video.id}" }) { _, entry ->
                            InspectionFileCard(entry, false, false, onPlay, onDelete,
                                onMissing = if (actionsEnabled) ({ pendingMissing = entry.video }) else null)
                        }
                        item { Text(stringResource(R.string.library_duplicates, result.duplicates.size), style = MaterialTheme.typography.titleMedium) }
                        result.duplicates.forEachIndexed { groupIndex, duplicate ->
                            item(key = "duplicate-header-$groupIndex") {
                                Text(stringResource(if (duplicate.sameReference) R.string.library_duplicate_refs else R.string.library_duplicate_hash),
                                    style = MaterialTheme.typography.labelLarge)
                            }
                            itemsIndexed(duplicate.files, key = { _, file -> "duplicate-$groupIndex-${file.video.id}" }) { _, entry ->
                                InspectionFileCard(entry, actionsEnabled, actionsEnabled && entry.video.id !in sharedIds, onPlay, onDelete)
                            }
                        }
                        item { Text(stringResource(R.string.library_large_files, result.largeFiles.size, result.thresholdMiB), style = MaterialTheme.typography.titleMedium) }
                        itemsIndexed(result.largeFiles, key = { _, file -> "large-${file.video.id}" }) { _, entry ->
                            InspectionFileCard(entry, actionsEnabled, actionsEnabled && entry.video.id !in sharedIds, onPlay, onDelete)
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !removing, onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    if (state.running) TextButton(onClick = onCancel) { Text(stringResource(R.string.library_scan_cancel)) }
                    else TextButton(enabled = thresholdValue != null && !removing,
                        onClick = { removalFailed = false; onStart(requireNotNull(thresholdValue)) }) {
                        Text(stringResource(R.string.library_scan_start))
                    }
                }
            }
        }
    }
    pendingMissing?.let { expected ->
        ConfirmDialog(visible = true, title = stringResource(R.string.library_remove_missing),
            message = stringResource(R.string.library_remove_missing_help),
            confirmText = stringResource(R.string.confirm), dismissText = stringResource(R.string.cancel),
            onConfirm = {
                pendingMissing = null; removing = true
                scope.launch {
                    try { removalFailed = !onRemoveMissing(expected) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { removalFailed = true }
                    finally { removing = false }
                }
            }, onDismiss = { pendingMissing = null })
    }
}

@Composable
private fun InspectionFileCard(
    entry: InspectedFile, canPlay: Boolean, canDelete: Boolean,
    onPlay: (HanimeDownloadEntity) -> Unit, onDelete: (HanimeDownloadEntity) -> Unit,
    onMissing: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${entry.video.title} · ${entry.video.quality}", style = MaterialTheme.typography.titleSmall)
            Text(entry.video.videoCode, style = MaterialTheme.typography.bodySmall)
            Text(if (entry.probe.status == FileStatus.AVAILABLE) {
                if (entry.probe.bytes >= 0) Formatter.formatShortFileSize(context, entry.probe.bytes)
                else stringResource(R.string.library_size_unknown)
            } else stringResource(when (entry.probe.status) {
                FileStatus.MISSING -> R.string.library_file_missing
                FileStatus.EMPTY -> R.string.library_file_empty
                FileStatus.CHANGED -> R.string.library_file_changed
                else -> R.string.library_file_unavailable
            }), style = MaterialTheme.typography.bodySmall)
            FlowRow {
                if (canPlay) TextButton(onClick = { onPlay(entry.video) }) { Text(stringResource(R.string.library_check_play)) }
                if (canDelete) TextButton(onClick = { onDelete(entry.video) }) { Text(stringResource(R.string.delete)) }
                if (entry.probe.status == FileStatus.MISSING && onMissing != null) TextButton(onClick = onMissing) {
                    Text(stringResource(R.string.library_remove_missing))
                }
            }
        }
    }
}
