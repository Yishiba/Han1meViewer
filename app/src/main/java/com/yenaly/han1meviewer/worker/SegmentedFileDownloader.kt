package com.yenaly.han1meviewer.worker

import com.yenaly.han1meviewer.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLongArray

/** A server that does not honor bounded byte ranges must use the ordinary downloader. */
internal class SegmentRangeUnsupportedException : IOException("Bounded range unsupported")

/** Writes disjoint HTTP ranges into an already allocated private file. */
internal class SegmentedFileDownloader(
    private val client: OkHttpClient,
    private val fetch: suspend (Request) -> okhttp3.Response = { client.newCall(it).await() }
) {
    companion object {
        const val MIN_REMAINING_BYTES = 16L * 1024 * 1024
        private const val BUFFER_SIZE = 64 * 1024
        private val connectionSlots = Semaphore(4)
    }

    private data class Part(val start: Long, val end: Long) {
        val length get() = end - start + 1
    }

    /** [onCheckpoint] receives a durable contiguous prefix and total received bytes. */
    suspend fun download(
        url: String,
        file: RandomAccessFile,
        start: Long,
        total: Long,
        onCheckpoint: suspend (prefix: Long, received: Long) -> Unit
    ) = supervisorScope {
        require(start in 0 until total)
        val firstEnd = start + (total - start) / 2 - 1
        val parts = listOf(Part(start, firstEnd), Part(firstEnd + 1, total - 1))
        val written = AtomicLongArray(parts.size)
        val checkpointLock = Mutex()

        suspend fun checkpoint() = checkpointLock.withLock {
            val snapshot = LongArray(parts.size) { written.get(it) }
            var prefix = start
            for (index in parts.indices) {
                prefix += snapshot[index]
                if (snapshot[index] < parts[index].length) break
            }
            // Snapshot before fsync: never publish bytes written after the sync began.
            file.fd.sync()
            onCheckpoint(prefix, start + snapshot.sum())
        }

        val jobs = parts.mapIndexed { index, part ->
            async(Dispatchers.IO) {
                connectionSlots.withPermit {
                    var position = part.start
                    var lastCheckpoint = 0L
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (position <= part.end) {
                        val response = fetch(
                            Request.Builder().url(url)
                                .header("Accept-Encoding", "identity")
                                .header("Range", "bytes=$position-${part.end}")
                                .get().build()
                        )
                        response.use {
                            if (it.code == 408 || it.code == 429 || it.code in 500..599) {
                                throw IOException("HTTP ${it.code}")
                            }
                            val range = it.header("Content-Range")
                            val rangeEnd = ResumeRangeValidator.end(range, position, total)
                            if (it.code != 206 || rangeEnd == null || rangeEnd > part.end) {
                                throw SegmentRangeUnsupportedException()
                            }
                            val stream = it.body.byteStream()
                            var remaining = rangeEnd - position + 1
                            while (remaining > 0) {
                                val count = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (count < 0) throw IOException("Download incomplete: segment ended early")
                                if (count == 0) continue
                                val bytes = ByteBuffer.wrap(buffer, 0, count)
                                while (bytes.hasRemaining()) {
                                    val wrote = file.channel.write(bytes, position + bytes.position())
                                    if (wrote <= 0) throw IOException("Download write made no progress")
                                }
                                position += count
                                remaining -= count
                                written.addAndGet(index, count.toLong())
                                val now = System.currentTimeMillis()
                                if (now - lastCheckpoint >= HanimeDownloadWorker.RESPONSE_INTERVAL) {
                                    checkpoint()
                                    lastCheckpoint = now
                                }
                            }
                        }
                    }
                    checkpoint()
                }
            }
        }
        try {
            jobs.awaitAll()
        } catch (e: Exception) {
            jobs.forEach { it.cancel() }
            jobs.joinAll()
            throw e
        }
    }
}
