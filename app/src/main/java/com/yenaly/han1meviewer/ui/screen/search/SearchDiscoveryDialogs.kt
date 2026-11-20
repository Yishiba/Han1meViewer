package com.yenaly.han1meviewer.ui.screen.search

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.translation.SearchToolsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SearchNameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: suspend (String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial.take(80)) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it.take(80); failed = false }, enabled = !busy,
                label = { Text(stringResource(R.string.discovery_watch_name)) }, singleLine = true)
            if (failed) Text(stringResource(R.string.discovery_operation_failed), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton(enabled = !busy && name.isNotBlank(), onClick = {
            busy = true
            scope.launch {
                try { withContext(Dispatchers.IO) { onSave(name.trim()) }; onDismiss() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.confirm)) }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
fun SaveSearchAliasDialog(original: String, target: String, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { SearchToolsStore(context) }
    val scope = rememberCoroutineScope()
    var alias by remember { mutableStateOf(original.take(500)) }
    var query by remember { mutableStateOf(target.take(500)) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(R.string.discovery_save_alias)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(alias, { alias = it.take(500) }, enabled = !busy,
                label = { Text(stringResource(R.string.discovery_alias_original)) })
            OutlinedTextField(query, { query = it.take(500) }, enabled = !busy,
                label = { Text(stringResource(R.string.discovery_alias_target)) })
            if (failed) Text(stringResource(R.string.discovery_operation_failed), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton(enabled = !busy && alias.isNotBlank() && query.isNotBlank(), onClick = {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { store.saveAlias(alias, query) }
                    onSaved(); onDismiss()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.confirm)) }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
