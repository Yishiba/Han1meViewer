package com.yenaly.han1meviewer.ui.screen.settings.backup

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.backup.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BackupHistoryRoute(onDismiss: () -> Unit, onRestored: () -> Unit) {
    val context = LocalContext.current
    val service = remember { BackupService.get(context) }
    val state by service.state.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    val transfers = rememberBackupTransfers(onRestored)
    var passwordDialog by remember { mutableStateOf(false) }
    var disableEncryption by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun perform(success: Int? = null, action: suspend () -> Unit) {
        if (busy) return
        busy = true; message = null
        scope.launch {
            try { action(); message = success }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: BackupBusyException) { message = R.string.backup_downloads_busy }
            catch (_: Exception) { message = R.string.backup_operation_failed }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { perform { service.refresh() } }
    val directory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) perform {
            withContext(Dispatchers.IO) {
                context.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            service.configure(service.state.value.settings.copy(mirrorUri = uri.toString()))
        }
    }
    BackupHistoryDialog(state, busy, message, onDismiss,
        onConfigure = { config -> perform { service.configure(config) } },
        onCreate = { perform(R.string.backup_created) { service.create() } },
        onChooseDirectory = { directory.launch(null) },
        onRestore = { version -> perform { service.restore(version.id); onRestored() } },
        onExport = transfers.exportVersion,
        onDelete = { version -> perform(R.string.backup_deleted) { service.delete(version.id) } },
        onConfigureEncryption = { passwordDialog = true },
        onDisableEncryption = { disableEncryption = true },
    )
    if (passwordDialog) BackupPasswordDialog(export = true, title = R.string.backup_crypto_set_password, help = R.string.backup_crypto_copy_help, onDismiss = { passwordDialog = false }, onConfirm = { password ->
        passwordDialog = false
        perform { service.configureCopyPassword(requireNotNull(password)) }
    })
    if (disableEncryption) androidx.compose.material3.AlertDialog(onDismissRequest = { disableEncryption = false },
        title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.backup_crypto_disable)) },
        text = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.backup_crypto_disable_help)) },
        confirmButton = { androidx.compose.material3.TextButton({ disableEncryption = false; perform { service.configureCopyPassword(null) } }) {
            androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.confirm))
        } }, dismissButton = { androidx.compose.material3.TextButton({ disableEncryption = false }) {
            androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.cancel))
        } })
}
