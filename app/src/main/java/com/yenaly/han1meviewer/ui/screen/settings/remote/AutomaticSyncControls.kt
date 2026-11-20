package com.yenaly.han1meviewer.ui.screen.settings.remote

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.remote.*
import java.text.DateFormat
import java.util.Date

@Composable
fun AutomaticSyncControls(config: AutomaticSyncSettings, enabled: Boolean,
    onConfigure: (Boolean, Int, Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.auto_sync_title), Modifier.weight(1f))
            Switch(config.enabled, { onConfigure(it, config.minutes, config.unmetered) }, enabled = enabled,
                modifier = Modifier.testTag("remote.auto.enabled"))
        }
        Text(stringResource(R.string.auto_sync_help), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15 to R.string.auto_sync_15, 60 to R.string.auto_sync_60, 360 to R.string.auto_sync_360).forEach { (minutes, label) ->
                FilterChip(config.minutes == minutes, { onConfigure(config.enabled, minutes, config.unmetered) },
                    label = { Text(stringResource(label)) }, enabled = enabled,
                    modifier = Modifier.testTag("remote.auto.minutes.$minutes"))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(config.unmetered, { onConfigure(config.enabled, config.minutes, it) }, enabled = enabled,
                modifier = Modifier.testTag("remote.auto.unmetered"))
            Text(stringResource(R.string.auto_sync_unmetered), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
        val message = when (config.status) {
            AutomaticSyncStatus.IDLE -> R.string.auto_sync_idle
            AutomaticSyncStatus.SUCCESS -> R.string.auto_sync_success
            AutomaticSyncStatus.CONFLICT -> R.string.auto_sync_conflict
            AutomaticSyncStatus.AUTH -> R.string.auto_sync_auth
            AutomaticSyncStatus.INVALID -> R.string.auto_sync_invalid
            AutomaticSyncStatus.BUSY -> R.string.auto_sync_busy
            AutomaticSyncStatus.RETRY -> R.string.auto_sync_retry
            AutomaticSyncStatus.LOCAL_CHANGED -> R.string.auto_sync_local_changed
        }
        Text(stringResource(message), modifier = Modifier.testTag("remote.auto.status"),
            color = if (config.blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        fun date(time: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
        if (config.lastAttempt > 0) Text(stringResource(R.string.auto_sync_last_attempt, date(config.lastAttempt)), style = MaterialTheme.typography.bodySmall)
        if (config.lastSuccess > 0) Text(stringResource(R.string.auto_sync_last_success, date(config.lastSuccess)), style = MaterialTheme.typography.bodySmall)
    }
}
