package com.yenaly.han1meviewer.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.ui.preview.ComponentPreview
import kotlinx.coroutines.delay

private const val NOTICE_SECONDS = 5

@Composable
fun UsageNoticeDialog(
    visible: Boolean,
    onAccepted: () -> Unit,
    onDeclined: () -> Unit,
) {
    if (!visible) return

    var remainingSeconds by rememberSaveable { mutableIntStateOf(NOTICE_SECONDS) }
    var showNoticePanel by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isResumed = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> isResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        delay(700)
        showNoticePanel = true
    }
    LaunchedEffect(isResumed, showNoticePanel) {
        while (isResumed && showNoticePanel && remainingSeconds > 0) {
            delay(1_000)
            remainingSeconds--
        }
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(R.drawable.han1me_launch_art),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            AnimatedVisibility(
                visible = showNoticePanel,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(280)) +
                    fadeIn(animationSpec = tween(280)),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.62f),
                    shape = RectangleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    tonalElevation = 8.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.usage_notice_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = stringResource(R.string.usage_notice_content),
                            modifier = Modifier.weight(1f).padding(vertical = 12.dp)
                                .verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Start,
                        )
                        Button(
                            onClick = onAccepted,
                            enabled = remainingSeconds == 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (remainingSeconds == 0) {
                                    stringResource(R.string.usage_notice_accept)
                                } else {
                                    stringResource(R.string.usage_notice_accept_countdown, remainingSeconds)
                                }
                            )
                        }
                        TextButton(onClick = onDeclined, modifier = Modifier.align(Alignment.End)) {
                            Text(stringResource(R.string.usage_notice_decline))
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun UsageNoticeDialogPreview() {
    ComponentPreview {
        UsageNoticeDialog(
            visible = true,
            onAccepted = {},
            onDeclined = {},
        )
    }
}
