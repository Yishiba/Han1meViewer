package com.yenaly.han1meviewer.ui.screen.settings.backup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.yenaly.han1meviewer.R

@Composable
fun BackupPasswordDialog(
    export: Boolean, onDismiss: () -> Unit, onConfirm: (String?) -> Unit,
    allowPlain: Boolean = false, busy: Boolean = false, error: Int? = null, title: Int? = null, help: Int? = null,
) {
    // Deliberately not saveable: never put a password in an Activity state bundle.
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var encrypted by remember { mutableStateOf(true) }
    val valid = if (!encrypted) allowPlain else password.length in (if (export) 12 else 1)..256 && (!export || password == confirmation)
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(title ?: if (export) R.string.backup_crypto_export else R.string.backup_crypto_unlock)) },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (allowPlain) Row {
                FilterChip(encrypted, { encrypted = true }, label = { Text(stringResource(R.string.backup_crypto_encrypted)) }, enabled = !busy)
                Spacer(Modifier.width(8.dp))
                FilterChip(!encrypted, { encrypted = false }, label = { Text(stringResource(R.string.backup_crypto_plain)) }, enabled = !busy,
                    modifier = Modifier.testTag("backup.crypto.plain"))
            }
            if (encrypted) {
                Text(stringResource(help ?: if (export) R.string.backup_crypto_password_help else R.string.backup_crypto_unlock_help))
                OutlinedTextField(password, { password = it.take(256) }, label = { Text(stringResource(R.string.backup_crypto_password)) },
                    modifier = Modifier.fillMaxWidth().testTag("backup.crypto.password"), singleLine = true, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                if (export) OutlinedTextField(confirmation, { confirmation = it.take(256) }, label = { Text(stringResource(R.string.backup_crypto_confirmation)) },
                    modifier = Modifier.fillMaxWidth().testTag("backup.crypto.confirmation"), singleLine = true, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
            } else Text(stringResource(R.string.backup_crypto_plain_warning))
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } }, confirmButton = {
            TextButton({ onConfirm(if (encrypted) password else null) }, enabled = valid && !busy,
                modifier = Modifier.testTag("backup.crypto.submit")) { Text(stringResource(R.string.confirm)) }
        }, dismissButton = { TextButton(onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}
