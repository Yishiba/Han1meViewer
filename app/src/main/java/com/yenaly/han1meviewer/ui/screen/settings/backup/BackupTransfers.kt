package com.yenaly.han1meviewer.ui.screen.settings.backup

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.backup.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class BackupTransfers(val exportCurrent: () -> Unit, val exportVersion: (BackupVersion) -> Unit, val importBackup: () -> Unit)
private data class ExportSelection(val id: String?)

/** Shared manual export/import flow used by Settings and the version list. */
@Composable
fun rememberBackupTransfers(onRestored: () -> Unit): BackupTransfers {
    val context = LocalContext.current
    val service = remember { BackupService.get(context) }
    val scope = rememberCoroutineScope()
    var selection by remember { mutableStateOf<ExportSelection?>(null) }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var unlockUri by remember { mutableStateOf<Uri?>(null) }
    var unlockError by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun toast(resource: Int) { Toast.makeText(context, resource, Toast.LENGTH_LONG).show() }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: BackupBusyException) { toast(R.string.backup_downloads_busy) }
            catch (_: BackupUnlockException) { unlockError = R.string.backup_crypto_wrong_password }
            catch (_: Exception) { toast(R.string.backup_operation_failed) }
            finally { busy = false }
        }
    }
    val output = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingBytes
        pendingBytes = null
        if (uri == null) bytes?.fill(0)
        else if (bytes == null) toast(R.string.backup_crypto_restart_export)
        else perform {
            try { service.writeExport(bytes, uri); toast(R.string.backup_export_success) }
            finally { bytes.fill(0) }
        }
    }
    val input = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> importUri = uri }
    selection?.let { selected -> BackupPasswordDialog(export = true, allowPlain = true,
        onDismiss = { selection = null }, onConfirm = { password ->
            selection = null
            perform {
                pendingBytes = service.prepareExport(selected.id, password)
                output.launch("Han1meClient-${System.currentTimeMillis()}." + if (password == null) "json" else "hvbenc")
            }
        }) }
    importUri?.let { uri -> AlertDialog(onDismissRequest = { importUri = null },
        title = { Text(stringResource(R.string.backup_import_title)) }, text = { Text(stringResource(R.string.backup_import_confirm_message)) },
        confirmButton = { TextButton({
            importUri = null
            perform {
                try { service.import(uri); toast(R.string.backup_import_success); onRestored() }
                catch (_: BackupPasswordRequiredException) { unlockUri = uri; unlockError = null }
            }
        }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton({ importUri = null }) { Text(stringResource(R.string.cancel)) } }) }
    unlockUri?.let { uri -> BackupPasswordDialog(export = false, busy = busy, error = unlockError,
        onDismiss = { unlockUri = null; unlockError = null }, onConfirm = { password ->
            unlockError = null
            perform {
                service.import(uri, password)
                unlockUri = null
                toast(R.string.backup_import_success)
                onRestored()
            }
        }) }
    if (busy && unlockUri == null) AlertDialog(onDismissRequest = {},
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.backup_working)) }, text = { LinearProgressIndicator() }, confirmButton = {})
    return BackupTransfers(
        exportCurrent = { selection = ExportSelection(null) },
        exportVersion = { selection = ExportSelection(it.id) },
        importBackup = { input.launch(arrayOf("application/json", "application/octet-stream", "text/*", "*/*")) },
    )
}
