package com.yenaly.han1meviewer.ui.screen.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.discovery.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedSearchUpdatesDialog(
    onDismiss: () -> Unit,
    onSearch: (SearchCriteria) -> Unit,
    onVideo: (String) -> Unit,
    repository: SavedSearchRepository? = null,
) {
    val context = LocalContext.current
    val repo = remember(context, repository) { repository ?: SavedSearchService.get(context) }
    val data by repo.state.collectAsStateWithLifecycle()
    val checking by repo.checking.collectAsStateWithLifecycle()
    val unreadable by repo.unreadable.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<WatchSearch?>(null) }
    var remove by remember { mutableStateOf<String?>(null) }
    fun action(block: suspend () -> Unit) {
        scope.launch {
            error = false
            try { withContext(Dispatchers.IO) { block() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = true }
        }
    }
    LaunchedEffect(repo) { action { repo.refresh(); repo.check() } }

    rename?.let { search ->
        SearchNameDialog(stringResource(R.string.discovery_rename), search.name, { rename = null }) { repo.rename(search.id, it) }
    }
    remove?.let { id ->
        AlertDialog(onDismissRequest = { remove = null }, title = { Text(stringResource(R.string.discovery_remove)) },
            text = { Text(stringResource(R.string.discovery_remove_confirm)) },
            confirmButton = { TextButton(onClick = { remove = null; action { repo.remove(id) } }) { Text(stringResource(R.string.confirm)) } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text(stringResource(R.string.cancel)) } })
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.discovery_watchlist), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.discovery_watch_hint), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp))
            TextButton(enabled = !checking && !unreadable, onClick = { action { repo.check(force = true) } }) {
                Text(stringResource(if (checking) R.string.discovery_checking else R.string.discovery_check_updates))
            }
            if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error || unreadable) Text(stringResource(if (unreadable) R.string.discovery_storage_unreadable else R.string.discovery_operation_failed),
                color = MaterialTheme.colorScheme.error)
            if (data.searches.isEmpty() && !unreadable) Text(stringResource(R.string.discovery_watch_empty), Modifier.padding(vertical = 16.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                data.searches.forEach { search ->
                    item(key = "search:${search.id}") {
                        val sameSite = search.criteria.baseUrl == Preferences.baseUrl
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            HorizontalDivider()
                            Text(search.name, style = MaterialTheme.typography.titleMedium)
                            Text(search.criteria.queries.filterNotNull().joinToString(" / "), maxLines = 2, style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.discovery_watch_unread, search.unreadCount), color = MaterialTheme.colorScheme.primary)
                            if (!search.initialized) Text(stringResource(R.string.discovery_watch_baseline), style = MaterialTheme.typography.bodySmall)
                            if (search.lastSuccess > 0) Text(stringResource(R.string.discovery_watch_last_check,
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(search.lastSuccess))),
                                style = MaterialTheme.typography.bodySmall)
                            if (search.failed) Text(stringResource(R.string.discovery_watch_failed), color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                            if (!sameSite) Text(stringResource(R.string.discovery_site_mismatch), style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(enabled = sameSite && !unreadable, onClick = { onDismiss(); onSearch(search.criteria) }) { Text(stringResource(R.string.discovery_reopen)) }
                                TextButton(enabled = !unreadable, onClick = { action { repo.setEnabled(search.id, !search.enabled) } }) {
                                    Text(stringResource(if (search.enabled) R.string.discovery_pause else R.string.discovery_resume))
                                }
                                TextButton(enabled = !unreadable, onClick = { rename = search }) { Text(stringResource(R.string.discovery_rename)) }
                                TextButton(enabled = !unreadable, onClick = { remove = search.id }) { Text(stringResource(R.string.discovery_remove)) }
                                TextButton(enabled = !unreadable && search.unreadCount > 0, onClick = { action { repo.markRead(search.id) } }) { Text(stringResource(R.string.discovery_read_all)) }
                            }
                        }
                    }
                    items(search.updates, key = { "update:${search.id}:${it.code}" }) { video ->
                        Column(Modifier.fillMaxWidth().clickable(enabled = !unreadable && search.criteria.baseUrl == Preferences.baseUrl) {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { repo.markRead(search.id, video.code) }
                                    onDismiss(); onVideo(video.code)
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { error = true }
                            }
                        }.padding(vertical = 8.dp)) {
                            Text(video.title, style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(if (video.read) R.string.discovery_read else R.string.discovery_unread),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
