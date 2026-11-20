package com.yenaly.han1meviewer.ui.screen.home.download

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.entity.download.DownloadGroupEntity
import com.yenaly.han1meviewer.logic.library.CollectionCatalog
import com.yenaly.han1meviewer.logic.library.GroupAssignment
import com.yenaly.han1meviewer.logic.library.SmartCollection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun LibraryOrganizerDialog(
    catalog: CollectionCatalog,
    groups: List<DownloadGroupEntity>,
    onDismiss: () -> Unit,
    onSave: suspend (SmartCollection) -> Boolean,
    onMove: suspend (String, Int) -> Boolean,
    onPreview: suspend () -> List<GroupAssignment>,
    onApply: suspend (List<GroupAssignment>) -> Int,
) {
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<List<GroupAssignment>?>(null) }
    var applied by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(catalog) { plan = null }
    fun perform(action: suspend () -> Unit) {
        busy = true; failed = false; applied = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true; plan = null }
            finally { busy = false }
        }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth(.94f).fillMaxHeight(.88f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.library_organize), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.library_organize_help), style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failed || catalog.readError) Text(stringResource(R.string.library_organize_error), color = MaterialTheme.colorScheme.error)
                applied?.let { Text(stringResource(R.string.library_organize_done, it)) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (catalog.collections.isEmpty()) item { Text(stringResource(R.string.library_organize_empty)) }
                    itemsIndexed(catalog.collections, key = { _, rule -> rule.id }) { index, rule ->
                        val target = groups.find { it.id == rule.autoTargetGroupId && it.id != DownloadGroupEntity.DEFAULT_GROUP_ID }
                        ElevatedCard {
                            Column(Modifier.padding(12.dp)) {
                                Text("${index + 1}. ${rule.name}", style = MaterialTheme.typography.titleSmall)
                                Text(target?.name ?: stringResource(R.string.library_auto_target_required), style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Switch(checked = rule.autoEnabled, enabled = !busy && !catalog.readError && (rule.autoEnabled || target != null),
                                        modifier = Modifier.testTag("library.auto.switch.${rule.id}").semantics { contentDescription = rule.name },
                                        onCheckedChange = { enabled -> perform { check(onSave(rule.copy(autoEnabled = enabled))) } })
                                    TextButton(enabled = index > 0 && !busy && !catalog.readError,
                                        modifier = Modifier.testTag("library.auto.up.${rule.id}"),
                                        onClick = { perform { check(onMove(rule.id, -1)) } }) { Text(stringResource(R.string.library_rule_up)) }
                                    TextButton(enabled = index < catalog.collections.lastIndex && !busy && !catalog.readError,
                                        modifier = Modifier.testTag("library.auto.down.${rule.id}"),
                                        onClick = { perform { check(onMove(rule.id, 1)) } }) { Text(stringResource(R.string.library_rule_down)) }
                                }
                            }
                        }
                    }
                    plan?.let { preview ->
                        item { Text(stringResource(R.string.library_organize_preview, preview.size), style = MaterialTheme.typography.titleMedium) }
                        items(preview, key = { "preview-${it.recordId}" }) { assignment ->
                            Text("${assignment.title} · ${assignment.quality}\n→ ${groups.find { it.id == assignment.targetGroupId }?.name.orEmpty()}",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
                    TextButton(enabled = !busy && !catalog.readError, onClick = { perform { plan = onPreview() } }) {
                        Text(stringResource(R.string.library_preview_organize))
                    }
                    TextButton(enabled = !busy && !plan.isNullOrEmpty(), onClick = {
                        val expected = plan ?: return@TextButton
                        perform { applied = onApply(expected); plan = null }
                    }) { Text(stringResource(R.string.library_confirm_organize)) }
                }
            }
        }
    }
}
