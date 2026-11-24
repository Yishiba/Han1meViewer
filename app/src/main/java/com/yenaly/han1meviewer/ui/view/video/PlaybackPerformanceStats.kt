package com.yenaly.han1meviewer.ui.view.video

import android.content.Context
import android.os.SystemClock

/** Records only the last ExoPlayer play-button-to-first-frame duration on this device. */
internal object PlaybackPerformanceStats {
    private const val STORE = "playback_performance_sample"
    private var startedAt = 0L

    @Synchronized fun begin() {
        startedAt = SystemClock.elapsedRealtime()
    }

    @Synchronized fun firstFrame(context: Context) {
        val start = startedAt
        startedAt = 0L
        if (start == 0L) return
        val elapsed = SystemClock.elapsedRealtime() - start
        if (elapsed !in 1..120_000) return
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putLong("exo_first_frame_ms", elapsed).apply()
    }

    fun latest(context: Context): Long? = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        .getLong("exo_first_frame_ms", 0L).takeIf { it > 0 }
}
