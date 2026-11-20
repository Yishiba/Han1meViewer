package com.yenaly.han1meviewer.ui.screen.settings.commerce

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.commerce.MembershipApiClient
import com.yenaly.han1meviewer.logic.commerce.MembershipConfig
import com.yenaly.han1meviewer.logic.commerce.MembershipCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Account and membership status only; payment and feature gates remain disabled. */
@Composable
fun MembershipDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { MembershipCredentialStore(context) }
    var client by remember { mutableStateOf<MembershipApiClient?>(null) }
    var auth by remember { mutableStateOf(MembershipCredentialStore.AuthState()) }
    var membership by remember { mutableStateOf<MembershipApiClient.MembershipStatus?>(null) }
    var orderNo by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(store) {
        try {
            val loadedClient = withContext(Dispatchers.IO) {
                val deviceId = store.deviceId()
                MembershipConfig.client(deviceId)
            }
            client = loadedClient
            val (loadedAuth, wasCorrupt) = withContext(Dispatchers.IO) {
                try {
                    store.load() to false
                } catch (_: Exception) {
                    store.clear()
                    MembershipCredentialStore.AuthState() to true
                }
            }
            auth = loadedAuth
            if (wasCorrupt) error = true
            val token = loadedAuth.sessionToken
            if (token != null && loadedClient != null) {
                try {
                    membership = loadedClient.refresh(token)
                } catch (expired: MembershipApiClient.MembershipHttpException) {
                    if (expired.statusCode != 401) throw expired
                    withContext(Dispatchers.IO) { store.clear() }
                    auth = MembershipCredentialStore.AuthState()
                    error = true
                }
            }
        } catch (_: Exception) {
            error = true
        } finally {
            busy = false
        }
    }

    fun runAction(action: suspend (MembershipApiClient) -> Unit) {
        val api = client ?: return
        scope.launch {
            busy = true
            error = false
            notice = null
            try {
                action(api)
            } catch (expired: MembershipApiClient.MembershipHttpException) {
                if (expired.statusCode == 401) {
                    withContext(Dispatchers.IO) { store.clear() }
                    auth = MembershipCredentialStore.AuthState()
                    membership = null
                }
                error = true
            } catch (_: Exception) {
                error = true
            } finally {
                busy = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.membership_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.membership_description),
                    style = MaterialTheme.typography.bodyMedium)

                val token = auth.sessionToken
                Text(
                    when {
                        token == null && auth.loginTicket != null -> stringResource(R.string.membership_waiting_login)
                        token == null -> stringResource(R.string.membership_not_connected)
                        membership?.active == true -> {
                            val until = membership?.entitlement?.paidUntilSeconds ?: 0L
                            val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(Date(until.coerceAtMost(Long.MAX_VALUE / 1000) * 1000))
                            stringResource(R.string.membership_active_until, date)
                        }
                        else -> stringResource(R.string.membership_no_active_plan)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (busy) Text(stringResource(R.string.membership_loading))
                if (error) Text(stringResource(R.string.membership_error),
                    color = MaterialTheme.colorScheme.error)
                notice?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }

                if (token == null) {
                    Button(onClick = {
                        runAction { api ->
                            val started = api.startLogin()
                            auth = withContext(Dispatchers.IO) {
                                store.saveLoginTicket(started.loginTicket)
                                store.load()
                            }
                            context.startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse(started.authorizationUrl)).addCategory(Intent.CATEGORY_BROWSABLE))
                            notice = R.string.membership_return_from_browser
                        }
                    }, enabled = !busy && client != null, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.membership_connect))
                    }
                    if (auth.loginTicket != null) {
                        TextButton(onClick = {
                            runAction { api ->
                                val session = api.completeLogin(requireNotNull(auth.loginTicket))
                                if (session == null) {
                                    notice = R.string.membership_waiting_login
                                } else {
                                    auth = withContext(Dispatchers.IO) {
                                        store.saveSessionToken(session)
                                        store.load()
                                    }
                                    membership = api.refresh(session)
                                }
                            }
                        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.membership_finish_login))
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = orderNo,
                        onValueChange = { orderNo = it.take(128) },
                        label = { Text(stringResource(R.string.membership_order_optional)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = {
                        runAction { api ->
                            membership = api.refresh(token, orderNo.trim().ifEmpty { null })
                            orderNo = ""
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.membership_refresh))
                    }
                    TextButton(onClick = {
                        runAction { api ->
                            try {
                                api.logout(token)
                            } finally {
                                withContext(Dispatchers.IO) { store.clear() }
                                auth = MembershipCredentialStore.AuthState()
                                membership = null
                            }
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.membership_disconnect))
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.back))
                }
            }
        }
    }
}
