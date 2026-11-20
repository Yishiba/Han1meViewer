package com.yenaly.han1meviewer.ui.screen.home.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.entity.download.DownloadGroupEntity
import com.yenaly.han1meviewer.logic.library.CollectionCatalog
import com.yenaly.han1meviewer.logic.library.LibrarySort
import com.yenaly.han1meviewer.logic.library.MediaLibraryRule
import com.yenaly.han1meviewer.logic.library.SmartCollection
import com.yenaly.han1meviewer.ui.component.ConfirmDialog
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun MediaLibraryRuleDialog(
    initial: MediaLibraryRule,
    collection: SmartCollection?,
    catalog: CollectionCatalog,
    groups: List<DownloadGroupEntity>,
    qualities: List<String>,
    onDismiss: () -> Unit,
    onApply: (MediaLibraryRule) -> Unit,
    onSave: suspend (SmartCollection) -> Boolean,
    onSaved: (SmartCollection) -> Unit,
    onDelete: suspend (String) -> Boolean,
    onDeleted: () -> Unit,
) {
    var draft by rememberSaveable(stateSaver = LibraryRuleSaver) { mutableStateOf(initial) }
    var minSize by rememberSaveable { mutableStateOf(initial.minSizeMiB?.toString().orEmpty()) }
    var maxSize by rememberSaveable { mutableStateOf(initial.maxSizeMiB?.toString().orEmpty()) }
    var days by rememberSaveable { mutableStateOf(initial.recentDays?.toString().orEmpty()) }
    var name by rememberSaveable { mutableStateOf(collection?.name.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var autoEnabled by rememberSaveable { mutableStateOf(collection?.autoEnabled ?: false) }
    var targetGroup by rememberSaveable { mutableStateOf(collection?.autoTargetGroupId) }
    val scope = rememberCoroutineScope()
    val candidate = draft.withInputBounds(minSize, maxSize, days)
    val validName = name.isNotBlank() && name.trim().length <= 80 && catalog.collections.none {
        it.id != collection?.id && it.name.equals(name.trim(), ignoreCase = true)
    }
    val validTarget = !autoEnabled || groups.any { it.id == targetGroup && it.id != DownloadGroupEntity.DEFAULT_GROUP_ID }
    val autoLabel = stringResource(R.string.library_auto_enable)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.library_edit)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.library_rules_help), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, enabled = !busy,
                    label = { Text(stringResource(R.string.library_name)) },
                    supportingText = { Text(stringResource(R.string.library_name_help)) },
                    isError = name.isNotEmpty() && !validName, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("library.name"),
                )
                OutlinedTextField(
                    value = draft.keyword, onValueChange = { draft = draft.copy(keyword = it) }, enabled = !busy,
                    label = { Text(stringResource(R.string.library_keyword)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("library.rule.keyword"),
                )
                OutlinedTextField(
                    value = draft.excludedKeyword, onValueChange = { draft = draft.copy(excludedKeyword = it) }, enabled = !busy,
                    label = { Text(stringResource(R.string.library_excluded)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("library.rule.excluded"),
                )
                Text(stringResource(R.string.library_groups))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (groups.map { it.id } + draft.groupIds).distinct().forEach { id ->
                        FilterChip(
                            selected = id in draft.groupIds, enabled = !busy,
                            modifier = Modifier.testTag("library.group.$id"),
                            onClick = { draft = draft.copy(groupIds = draft.groupIds.toggle(id)) },
                            label = { Text(groups.find { it.id == id }?.name ?: stringResource(R.string.library_missing_group, id)) },
                        )
                    }
                }
                Text(stringResource(R.string.library_qualities))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (qualities + draft.qualities).distinct().sorted().forEach { quality ->
                        FilterChip(
                            selected = quality in draft.qualities, enabled = !busy,
                            modifier = Modifier.testTag("library.quality.$quality"),
                            onClick = { draft = draft.copy(qualities = draft.qualities.toggle(quality)) },
                            label = { Text(quality.ifBlank { stringResource(R.string.library_unknown_quality) }) },
                        )
                    }
                }
                LibraryNumberField(minSize, { minSize = it }, R.string.library_min_size, !busy)
                LibraryNumberField(maxSize, { maxSize = it }, R.string.library_max_size, !busy)
                LibraryNumberField(days, { days = it }, R.string.library_recent_days, !busy)
                if (candidate == null) Text(stringResource(R.string.library_bounds_error), color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.library_sort))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibrarySort.entries.forEach { sort ->
                        FilterChip(
                            selected = draft.sort == sort, enabled = !busy,
                            modifier = Modifier.testTag("library.sort.$sort"),
                            onClick = { draft = draft.copy(sort = sort) },
                            label = { Text(stringResource(when (sort) {
                                LibrarySort.NEWEST -> R.string.library_newest
                                LibrarySort.OLDEST -> R.string.library_oldest
                                LibrarySort.TITLE -> R.string.library_title_sort
                                LibrarySort.LARGEST -> R.string.library_largest
                                LibrarySort.SMALLEST -> R.string.library_smallest
                            })) },
                        )
                    }
                }
                Row {
                    Text(autoLabel, modifier = Modifier.weight(1f))
                    Switch(checked = autoEnabled, onCheckedChange = { autoEnabled = it }, enabled = !busy,
                        modifier = Modifier.testTag("library.auto.enabled").semantics { contentDescription = autoLabel })
                }
                Text(stringResource(R.string.library_auto_help), style = MaterialTheme.typography.bodySmall)
                if (autoEnabled) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        groups.filter { it.id != DownloadGroupEntity.DEFAULT_GROUP_ID }.forEach { group ->
                            FilterChip(selected = group.id == targetGroup, enabled = !busy,
                                modifier = Modifier.testTag("library.auto.target.${group.id}"),
                                onClick = { targetGroup = group.id }, label = { Text(group.name) })
                        }
                    }
                    if (!validTarget) Text(stringResource(R.string.library_auto_target_required), color = MaterialTheme.colorScheme.error)
                }
                if (collection != null) TextButton(onClick = { confirmDelete = true }, enabled = !busy) {
                    Text(stringResource(R.string.library_delete))
                }
                if (failed) Text(stringResource(R.string.library_save_error), color = MaterialTheme.colorScheme.error)
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = candidate != null && !busy, onClick = { candidate?.let(onApply) }) {
                    Text(stringResource(R.string.library_apply))
                }
                TextButton(enabled = candidate != null && validName && validTarget && !catalog.readError && !busy, onClick = {
                    val rule = candidate ?: return@TextButton
                    busy = true
                    scope.launch {
                        val saved = SmartCollection(collection?.id ?: UUID.randomUUID().toString(), name.trim(), rule, autoEnabled, targetGroup)
                        if (onSave(saved)) onSaved(saved) else failed = true
                        busy = false
                    }
                }) { Text(stringResource(R.string.library_save)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
        },
    )
    ConfirmDialog(
        visible = confirmDelete, title = stringResource(R.string.library_delete),
        message = stringResource(R.string.library_delete_message),
        confirmText = stringResource(R.string.confirm), dismissText = stringResource(R.string.cancel),
        onConfirm = {
            confirmDelete = false
            busy = true
            scope.launch {
                if (collection != null && onDelete(collection.id)) onDeleted() else failed = true
                busy = false
            }
        },
        onDismiss = { confirmDelete = false },
    )
}

@Composable
private fun LibraryNumberField(value: String, onChange: (String) -> Unit, label: Int, enabled: Boolean) {
    OutlinedTextField(
        value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
        label = { Text(stringResource(label)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
