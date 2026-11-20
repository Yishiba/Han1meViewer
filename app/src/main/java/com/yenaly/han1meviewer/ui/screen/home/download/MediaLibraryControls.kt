package com.yenaly.han1meviewer.ui.screen.home.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.library.CollectionCatalog
import com.yenaly.han1meviewer.logic.library.MediaLibraryCodec
import com.yenaly.han1meviewer.logic.library.MediaLibraryRule
import com.yenaly.han1meviewer.logic.library.SmartCollection

val LibraryRuleSaver: Saver<MediaLibraryRule, String> = Saver(
    save = { rule -> MediaLibraryCodec.encodeRule(rule) },
    restore = { runCatching { MediaLibraryCodec.decodeRule(it) }.getOrNull() },
)

@Composable
fun MediaLibraryControls(
    rule: MediaLibraryRule,
    selectedId: String?,
    catalog: CollectionCatalog,
    matched: Int,
    total: Int,
    onKeyword: (String) -> Unit,
    onSelect: (SmartCollection) -> Unit,
    onReset: () -> Unit,
    onEdit: () -> Unit,
    onOrganize: () -> Unit = {},
    onInspect: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = rule.keyword,
            onValueChange = onKeyword,
            label = { Text(stringResource(R.string.library_keyword)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("library.search"),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = selectedId == null && rule == MediaLibraryRule(),
                    onClick = onReset,
                    label = { Text(stringResource(R.string.library_all)) },
                )
            }
            items(catalog.collections, key = { it.id }) { collection ->
                FilterChip(
                    selected = selectedId == collection.id,
                    onClick = { onSelect(collection) },
                    label = { Text(collection.name) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.library_count, matched, total),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onReset) { Text(stringResource(R.string.library_reset)) }
            TextButton(onClick = onEdit) { Text(stringResource(R.string.library_edit)) }
        }
        if (catalog.readError) {
            Text(
                stringResource(R.string.library_read_error),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onOrganize) { Text(stringResource(R.string.library_organize)) }
            TextButton(onClick = onInspect) { Text(stringResource(R.string.library_inspect)) }
        }
    }
}
