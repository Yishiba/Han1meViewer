package com.yenaly.han1meviewer.ui.screen.settings.commerce

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.commerce.TrialResearchStore

@Composable
fun TrialResearchDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { TrialResearchStore(context) }
    val answer = remember { mutableStateOf(store.answer) }
    val days = store.daysRemaining()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trial_research_title)) },
        text = {
            Column {
                Text(when {
                    store.isExistingUser -> stringResource(R.string.trial_research_existing)
                    days == null -> stringResource(R.string.trial_research_not_started)
                    days > 0 -> stringResource(R.string.trial_research_days, days)
                    else -> stringResource(R.string.trial_research_ended)
                })
                Text(stringResource(R.string.trial_research_quote, store.quoteYuan), Modifier.padding(top = 12.dp))
                Text(stringResource(R.string.trial_research_disclaimer), Modifier.padding(top = 8.dp))
                listOf(
                    "consider" to R.string.trial_research_consider,
                    "conditional" to R.string.trial_research_conditional,
                    "decline" to R.string.trial_research_decline,
                    "undecided" to R.string.trial_research_undecided,
                ).forEach { (value, label) ->
                    TextButton(onClick = { store.recordAnswer(value); answer.value = value }, Modifier.fillMaxWidth()) {
                        Text(stringResource(label) + if (answer.value == value) " ✓" else "")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm)) } },
    )
}
