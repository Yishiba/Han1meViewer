package com.yenaly.han1meviewer.ui.screen.settings.remote

import android.view.SurfaceView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DataSource
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.remote.*
import com.yenaly.han1meviewer.logic.backup.BackupService
import kotlinx.coroutines.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun RemotePlayerDialog(entry: DavEntry, client: WebDavClient, profile: DavProfile, store: RemoteStore, initial: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(entry.url) { ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DataSource.Factory { DavMediaDataSource(client) })).build() }
    val saves = remember(player) { kotlinx.coroutines.channels.Channel<RemoteProgress>(kotlinx.coroutines.channels.Channel.UNLIMITED) }
    var position by remember { mutableLongStateOf(initial) }
    var duration by remember { mutableLongStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var persistenceFailed by remember { mutableStateOf(false) }
    fun save() {
        if (player.playbackState == Player.STATE_IDLE || failed) return
        val progress = RemoteProgress(entry.url, entry.name, player.currentPosition.coerceAtLeast(0), System.currentTimeMillis())
        saves.trySend(progress)

    }
    DisposableEffect(player, lifecycle) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (progress in saves) {
                try { BackupService.get(context).withDownloadAccess { store.saveProgress(progress, profile) } }
                catch (_: Exception) { persistenceFailed = true }
            }
        }
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failed = true; buffering = false }
            override fun onPlaybackStateChanged(playbackState: Int) { buffering = playbackState == Player.STATE_BUFFERING }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player.addListener(listener)
        player.setMediaItem(MediaItem.fromUri(entry.url), initial.coerceAtLeast(0))
        player.prepare(); player.playWhenReady = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { save(); player.pause() }
        }
        lifecycle.addObserver(observer)
        onDispose { save(); saves.close(); lifecycle.removeObserver(observer); player.removeListener(listener); player.release() }
    }
    LaunchedEffect(player) {
        var ticks = 0
        while (true) {
            delay(1000)
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.coerceAtLeast(0)
            if (++ticks % 10 == 0) save()
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.9f)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                if (buffering) LinearProgressIndicator(Modifier.fillMaxWidth())
                AndroidView(factory = { SurfaceView(it).also(player::setVideoSurfaceView) }, modifier = Modifier.fillMaxWidth().weight(1f))
                if (failed) Text(stringResource(R.string.remote_play_failed), color = MaterialTheme.colorScheme.error)
                if (persistenceFailed) Text(stringResource(R.string.remote_progress_failed), color = MaterialTheme.colorScheme.error)
                Text("${position / 1000}s / ${duration / 1000}s")
                Slider(value = position.coerceAtMost(duration).toFloat(), valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                    enabled = duration > 0 && !failed, onValueChange = { position = it.toLong(); player.seekTo(position) }, onValueChangeFinished = { save() })
                Row {
                    TextButton({ if (playing) { player.pause(); save() } else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() } }, enabled = !failed) {
                        Text(stringResource(if (playing) R.string.remote_pause else R.string.remote_play))
                    }
                    if (failed) TextButton({ failed = false; player.prepare(); player.play() }) { Text(stringResource(R.string.remote_retry)) }
                    TextButton(onDismiss) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}
