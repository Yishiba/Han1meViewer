package com.yenaly.han1meviewer.ui.screen.settings.backup

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.backup.BackupService
import com.yenaly.han1meviewer.ui.theme.HanimeTheme
import com.yenaly.han1meviewer.worker.HanimeDownloadManagerV2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun BackupRecoveryScreen(onRecovered: () -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    HanimeTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.backup_recovery_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.backup_recovery_help))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            BackupService.get(context).initialize()
                            HanimeDownloadManagerV2.init()
                            onRecovered()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Journal remains intact; retry stays available. */ }
                        finally { busy = false }
                    }
                }) { Text(stringResource(R.string.backup_retry_recovery)) }
                TextButton(onExit, enabled = !busy) { Text(stringResource(R.string.backup_exit)) }
            }
        }
    }
}
