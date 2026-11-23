package com.yenaly.han1meviewer.worker

import android.content.Context

/** Local, non-identifying samples of completed transfers. No URL, title or account is stored. */
internal object DownloadPerformanceStats {
    private const val STORE = "download_performance_samples"

    data class Sample(val bytes: Long, val durationMillis: Long, val connections: Int) {
        val mebibytesPerSecond: Double
            get() = bytes.toDouble() * 1_000 / durationMillis / (1024 * 1024)
    }

    fun record(context: Context, enhanced: Boolean, bytes: Long, durationMillis: Long, connections: Int) {
        if (bytes <= 0 || durationMillis <= 0) return
        val prefix = if (enhanced) "enhanced" else "standard"
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putLong("${prefix}_bytes", bytes)
            .putLong("${prefix}_duration", durationMillis)
            .putInt("${prefix}_connections", connections)
            .apply()
    }

    fun latest(context: Context, enhanced: Boolean): Sample? {
        val prefix = if (enhanced) "enhanced" else "standard"
        val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val bytes = store.getLong("${prefix}_bytes", 0)
        val duration = store.getLong("${prefix}_duration", 0)
        if (bytes <= 0 || duration <= 0) return null
        return Sample(bytes, duration, store.getInt("${prefix}_connections", 1))
    }
}
