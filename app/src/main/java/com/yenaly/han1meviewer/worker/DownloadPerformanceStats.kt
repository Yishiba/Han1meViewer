package com.yenaly.han1meviewer.worker

import android.content.Context

/** Local, non-identifying samples of completed transfers. No URL, title or account is stored. */
internal object DownloadPerformanceStats {
    private const val STORE = "download_performance_samples"
    private const val MAX_RECENT = 10

    data class Sample(val bytes: Long, val durationMillis: Long, val connections: Int) {
        val mebibytesPerSecond: Double
            get() = bytes.toDouble() * 1_000 / durationMillis / (1024 * 1024)
    }

    @Synchronized fun record(context: Context, enhanced: Boolean, bytes: Long, durationMillis: Long, connections: Int) {
        if (bytes <= 0 || durationMillis <= 0) return
        val prefix = if (enhanced) "enhanced" else "standard"
        val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val next = (recent(context, enhanced) + Sample(bytes, durationMillis, connections))
            .takeLast(MAX_RECENT)
        store.edit()
            .putLong("${prefix}_bytes", bytes)
            .putLong("${prefix}_duration", durationMillis)
            .putInt("${prefix}_connections", connections)
            .putString("${prefix}_recent", next.joinToString(";") {
                "${it.bytes},${it.durationMillis},${it.connections}"
            })
            .apply()
    }

    @Synchronized fun recent(context: Context, enhanced: Boolean): List<Sample> {
        val prefix = if (enhanced) "enhanced" else "standard"
        val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        return store.getString("${prefix}_recent", null)?.split(';')?.takeLast(MAX_RECENT)
            ?.mapNotNull { line ->
                val parts = line.split(',')
                if (parts.size != 3) return@mapNotNull null
                val bytes = parts[0].toLongOrNull() ?: return@mapNotNull null
                val duration = parts[1].toLongOrNull() ?: return@mapNotNull null
                val connections = parts[2].toIntOrNull() ?: return@mapNotNull null
                if (bytes <= 0 || duration <= 0 || connections !in 1..16) null
                else Sample(bytes, duration, connections)
            } ?: listOfNotNull(latest(context, enhanced))
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
