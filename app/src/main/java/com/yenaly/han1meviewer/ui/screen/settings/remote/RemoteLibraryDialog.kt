package com.yenaly.han1meviewer.ui.screen.settings.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.backup.BackupBusyException
import com.yenaly.han1meviewer.logic.remote.*
import kotlinx.coroutines.*

@Composable
fun RemoteBrowserRows(entries: List<DavEntry>, busy: Boolean, onOpen: (DavEntry) -> Unit) {
    entries.forEach { entry ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text(entry.name, style = MaterialTheme.typography.titleSmall)
                if (entry.bytes >= 0 && !entry.directory) Text("${entry.bytes / 1024} KiB", style = MaterialTheme.typography.bodySmall)
                TextButton({ onOpen(entry) }, enabled = !busy && (entry.directory || WebDavClient.playable(entry.name)),
                    modifier = Modifier.testTag("remote.open.${entry.name}")) {
                    Text(stringResource(if (entry.directory) R.string.remote_open_folder else R.string.remote_play))
                }
            }
        }
    }
}

@Composable
fun RemoteSyncPreview(changes: Int, conflicts: Int, busy: Boolean, onConfirm: () -> Unit) {
    Text(stringResource(R.string.remote_preview_summary, changes, conflicts))
    Text(stringResource(R.string.remote_confirm_help), style = MaterialTheme.typography.bodySmall)
    Button(onConfirm, enabled = !busy, modifier = Modifier.testTag("remote.sync.confirm")) { Text(stringResource(R.string.remote_sync_confirm)) }
}

@Composable
fun RemoteLibraryDialog(
    onDismiss: () -> Unit, onSynced: () -> Unit,
    storage: RemoteStore? = null,
    connection: (DavProfile) -> WebDavClient = { it.client() },
) {
    val context = LocalContext.current
    val store = remember { storage ?: RemoteStore(context) }
    val sync = remember { RemoteSyncService(context, store = store, clientFactory = connection) }
    val autoStore = remember { AutomaticSyncSettingsStore(context) }
    val autoScheduler = remember { AutomaticSyncScheduler(context, autoStore, store) }
    val autoFlow = remember { autoStore.observe() }
    val autoConfig by autoFlow.collectAsStateWithLifecycle(initialValue = autoStore.read())
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf(DavProfile()) }
    var configuration by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Int?>(null) }
    var folders by remember { mutableStateOf(emptyList<String>()) }
    var entries by remember { mutableStateOf(emptyList<DavEntry>()) }
    var plan by remember { mutableStateOf<RemoteSyncPlan?>(null) }
    var preferRemote by remember { mutableStateOf(true) }
    var player by remember { mutableStateOf<DavEntry?>(null) }
    var initialPosition by remember { mutableLongStateOf(0) }
    var confirmForget by remember { mutableStateOf(false) }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true; status = null
        scope.launch {
            try { withContext(Dispatchers.IO) { action() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: BackupBusyException) { status = R.string.backup_downloads_busy; plan = null }
            catch (_: DavConflictException) { status = R.string.remote_conflict_error; plan = null }
            catch (failure: Exception) {
                status = if (failure is DavException && failure.status in setOf(401, 403)) R.string.remote_auth_error else R.string.remote_error
                plan = null
            } finally { busy = false; loaded = true }
        }
    }
    LaunchedEffect(Unit) { perform {
        profile = store.read().profile
        configuration = profile.root.isBlank()
        if (profile.root.isNotBlank()) { folders = listOf(connection(profile).root.toString()); entries = connection(profile).list() }
    } }
    Dialog(onDismissRequest = { if (!busy && player == null) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.92f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.remote_title), style = MaterialTheme.typography.titleLarge)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                status?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.weight(1f).testTag("remote.list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Text(stringResource(R.string.remote_help), style = MaterialTheme.typography.bodySmall)
                        TextButton({ configuration = !configuration }, enabled = loaded && !busy) { Text(stringResource(R.string.remote_connection)) }
                        if (configuration) {
                            fun edit(next: DavProfile) { profile = next; plan = null; entries = emptyList(); folders = emptyList() }
                            OutlinedTextField(profile.root, { edit(profile.copy(root = it)) }, label = { Text(stringResource(R.string.remote_url)) },
                                enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("remote.url"), singleLine = true)
                            OutlinedTextField(profile.username, { edit(profile.copy(username = it)) }, label = { Text(stringResource(R.string.remote_username)) }, enabled = !busy, singleLine = true)
                            OutlinedTextField(profile.password, { edit(profile.copy(password = it)) }, label = { Text(stringResource(R.string.remote_password)) },
                                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), enabled = !busy, singleLine = true)
                            OutlinedTextField(profile.syncPassphrase, { edit(profile.copy(syncPassphrase = it)) }, label = { Text(stringResource(R.string.remote_passphrase)) },
                                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), enabled = !busy, singleLine = true)
                            Text(stringResource(R.string.remote_credentials_help), style = MaterialTheme.typography.bodySmall)
                            Button(enabled = !busy && profile.root.isNotBlank(), onClick = { perform {
                                profile.validate()
                                val client = connection(profile)
                                val rows = client.list()
                                profile = profile.copy(root = client.root.toString())
                                autoScheduler.configure(false, autoConfig.minutes, autoConfig.unmetered)
                                store.saveProfile(profile)
                                folders = listOf(profile.root); entries = rows; configuration = false; plan = null
                            } }) { Text(stringResource(R.string.remote_connect)) }
                        }
                    }
                    if (autoConfig.enabled || profile.root.isNotBlank()) item {
                        AutomaticSyncControls(autoConfig, loaded && !busy && (autoConfig.enabled ||
                            (profile.syncPassphrase.length >= 12 && !configuration))) { active, minutes, unmetered ->
                            perform {
                                if (active) check(store.read().profile == profile)
                                autoScheduler.configure(active, minutes, unmetered); plan = null
                            }
                        }
                    }
                    if (folders.isNotEmpty()) item {
                        Text(profile.root, style = MaterialTheme.typography.labelSmall)
                        Text(stringResource(R.string.remote_sync_help), style = MaterialTheme.typography.bodySmall)
                        Row {
                            Checkbox(preferRemote, { preferRemote = it; plan = null }, enabled = !busy)
                            Text(stringResource(R.string.remote_prefer_remote))
                        }
                        TextButton(enabled = !busy && profile.syncPassphrase.length >= 12, onClick = { perform {
                            check(store.read().profile == profile)
                            plan = sync.preview(preferRemote)
                        } }) { Text(stringResource(R.string.remote_sync_preview)) }
                        plan?.let { preview ->
                            RemoteSyncPreview(preview.changedKeys.size, preview.merged.conflicts, busy) {
                                perform { sync.apply(preview); autoScheduler.manualSuccess(); plan = null; withContext(Dispatchers.Main) { onSynced() } }
                            }
                        }
                        HorizontalDivider()
                        Text(folders.last(), style = MaterialTheme.typography.bodySmall)
                        FlowRow {
                            TextButton(enabled = !busy && folders.size > 1, onClick = { perform {
                                val stack = folders.dropLast(1)
                                entries = connection(profile).list(stack.last()); folders = stack
                            } }) { Text(stringResource(R.string.remote_parent)) }
                            TextButton(enabled = !busy, onClick = { perform { entries = connection(profile).list(folders.last()) } }) { Text(stringResource(R.string.remote_refresh)) }
                        }
                        if (entries.isEmpty()) Text(stringResource(R.string.remote_empty))
                    }
                    items(entries.chunked(30), key = { it.first().url }) { chunk ->
                        RemoteBrowserRows(chunk, busy) { entry ->
                            if (entry.directory) perform { entries = connection(profile).list(entry.url); folders = folders + entry.url }
                            else perform { initialPosition = store.read().progress[entry.url]?.position ?: 0; withContext(Dispatchers.Main) { player = entry } }
                        }
                    }
                    item { TextButton({ confirmForget = true }, enabled = loaded && !busy) { Text(stringResource(R.string.remote_forget)) } }
                }
                TextButton(onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
    player?.let { entry ->
        val client = remember(profile) { connection(profile) }
        RemotePlayerDialog(entry, client, profile, store, initialPosition, onDismiss = { player = null })
    }
    if (confirmForget) AlertDialog(onDismissRequest = { confirmForget = false }, title = { Text(stringResource(R.string.remote_forget)) },
        text = { Text(stringResource(R.string.remote_forget_help)) }, confirmButton = {
            TextButton({ confirmForget = false; perform { autoScheduler.configure(false, autoConfig.minutes, autoConfig.unmetered); store.clear(); withContext(Dispatchers.Main) { onDismiss() } } }) { Text(stringResource(R.string.confirm)) }
        }, dismissButton = { TextButton({ confirmForget = false }) { Text(stringResource(R.string.cancel)) } })
}
