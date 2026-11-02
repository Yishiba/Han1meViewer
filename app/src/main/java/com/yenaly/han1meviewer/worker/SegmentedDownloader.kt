package com.yenaly.han1meviewer.worker

import android.util.Log
import com.yenaly.han1meviewer.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

/**
 * 多连接分段下载。
 *
 * - 文件按字节区间切成若干段，每条连接用 Range 请求下载一段，按位置写进同一个文件；
 * - 某条连接下完自己的段后，把剩余最多的那段从中间劈开接着下，避免最后只剩一条慢连接拖尾；
 * - 各段进度编码成字符串交给 [onSave]，和已下载长度一起写进数据库，进程被杀后按段续传；
 * - 链接签名失效（403/410）时通过 [refreshUrl] 换新链接，多条连接共用一次刷新。
 *
 * @project Han1meViewer
 */
internal class SegmentedDownloader(
    private val client: () -> OkHttpClient,
    initialUrl: String,
    private val totalLength: Long,
    private val channel: FileChannel,
    private val connectionCount: Int,
    private val refreshUrl: suspend () -> String?,
    private val onSave: suspend (downloadedLength: Long, segments: String) -> Unit,
) {

    companion object {
        private const val TAG = "SegmentedDownloader"

        const val DEFAULT_CONNECTION_COUNT = 4

        /**
         * 小于两倍此值的段不再劈开，劈开点离当前写入位置也至少有这么远
         */
        private const val MIN_SPLIT_SIZE = 1024 * 1024L

        /**
         * 必须小于 [MIN_SPLIT_SIZE]，保证正在写的一块不会越过劈开点
         */
        private const val BUFFER_SIZE = 64 * 1024

        private const val SAVE_INTERVAL = 1_000L
        private const val MAX_SEGMENT_RETRY = 5
        private const val RETRY_DELAY = 2_000L

        private val LINK_EXPIRED_CODES = setOf(403, 410)
    }

    /**
     * 服务器对 Range 请求返回了 200，只能整文件单连接下载
     */
    class RangeNotSupportedException : Exception("Server ignored Range request")

    /**
     * 链接失效且刷新后仍被拒绝，或刷新失败
     */
    class LinkExpiredException(val code: Int) : Exception("Download link rejected: HTTP $code")

    class HttpStatusException(val code: Int, message: String) : Exception("HTTP $code $message")

    /**
     * 5xx，按网络抖动处理
     */
    class ServerErrorException(code: Int) : IOException("HTTP $code")

    /**
     * 负责 [start, end] 闭区间，[pos] 是下一个要写的位置。
     * [end] 可能被其他连接劈走一半而变小，读写都要在 [lock] 内进行。
     */
    private class Segment(val start: Long, var end: Long, var pos: Long) {
        var assigned = false
        val remaining get() = end - pos + 1
    }

    private val lock = Any()
    private val segments = mutableListOf<Segment>()
    private val activeCalls: MutableSet<Call> = ConcurrentHashMap.newKeySet()

    private val urlMutex = Mutex()

    @Volatile
    private var currentUrl = initialUrl
    private var urlRefreshed = false

    val downloadedLength: Long
        get() = synchronized(lock) { downloadedLengthLocked() }

    /**
     * @param savedSegments 上次保存的分段进度，null 表示没有
     * @param prefixLength 没有分段进度时，[0, prefixLength) 视为已下载（旧版单连接留下的进度）
     * @return 已下载长度，正常返回时等于文件总长度
     */
    suspend fun download(savedSegments: String?, prefixLength: Long): Long =
        withContext(Dispatchers.IO) {
            initSegments(savedSegments, prefixLength)
            Log.i(TAG, "start: length=$totalLength, segments=${encode()}")
            coroutineScope {
                // 作用域被取消（暂停、某条连接失败）时立刻掐断所有连接，阻塞中的 read 才能及时返回
                val canceller = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        activeCalls.forEach { it.cancel() }
                    }
                }
                val saver = launch {
                    while (true) {
                        delay(SAVE_INTERVAL)
                        save()
                    }
                }
                try {
                    List(connectionCount) { async { runConnection() } }.awaitAll()
                } finally {
                    canceller.cancel()
                    withContext(NonCancellable) {
                        // 等定时保存停下再做最后一次，避免两次写库交错
                        saver.cancelAndJoin()
                        save()
                    }
                }
            }
            downloadedLength
        }

    private fun initSegments(savedSegments: String?, prefixLength: Long) {
        if (savedSegments != null) {
            val restored = decode(savedSegments)
            if (restored != null) {
                segments += restored
                return
            }
            // 分段进度损坏时，数据库里的已下载长度是各段之和而不是前缀，只能从头下
            Log.w(TAG, "invalid saved segments, restart: $savedSegments")
            splitRange(0)
            return
        }
        val prefix = prefixLength.coerceIn(0, totalLength)
        if (prefix > 0) segments += Segment(0, prefix - 1, prefix)
        splitRange(prefix)
    }

    private fun splitRange(from: Long) {
        val rest = totalLength - from
        if (rest <= 0) return
        val count = (rest / MIN_SPLIT_SIZE).coerceIn(1, connectionCount.toLong())
        val size = rest / count
        for (i in 0 until count) {
            val start = from + i * size
            val end = if (i == count - 1) totalLength - 1 else start + size - 1
            segments += Segment(start, end, start)
        }
    }

    private suspend fun runConnection() {
        while (true) {
            val segment = takeSegment() ?: return
            downloadSegment(segment)
        }
    }

    /**
     * 优先领没人负责的段；都有人负责了就把剩余最多的那段劈一半过来
     */
    private fun takeSegment(): Segment? = synchronized(lock) {
        segments.firstOrNull { !it.assigned && it.remaining > 0 }?.let {
            it.assigned = true
            return it
        }
        val victim = segments
            .filter { it.remaining >= MIN_SPLIT_SIZE * 2 }
            .maxByOrNull { it.remaining } ?: return null
        val mid = victim.pos + victim.remaining / 2
        val stolen = Segment(mid, victim.end, mid).apply { assigned = true }
        victim.end = mid - 1
        segments.add(segments.indexOf(victim) + 1, stolen)
        stolen
    }

    private suspend fun downloadSegment(segment: Segment) {
        val buffer = ByteArray(BUFFER_SIZE)
        // 连续多少次没拿到新数据，有进展就清零
        var failures = 0
        while (true) {
            val (from, to) = synchronized(lock) { segment.pos to segment.end }
            if (from > to) return
            val url = currentUrl
            val request = Request.Builder().url(url).get()
                .header("Range", "bytes=$from-$to")
                .build()
            val call = client().newCall(request)
            activeCalls += call
            try {
                call.await().use { response ->
                    when (response.code) {
                        206 -> readBody(response, segment, buffer)
                        in LINK_EXPIRED_CODES -> onLinkRejected(url, response.code)
                        200 -> throw RangeNotSupportedException()
                        in 500..599 -> throw ServerErrorException(response.code)
                        else -> throw HttpStatusException(response.code, response.message)
                    }
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (!e.isTransientNetworkError()) throw e
                if (synchronized(lock) { segment.pos } > from) failures = 0
                if (++failures > MAX_SEGMENT_RETRY) throw e
                Log.w(TAG, "segment $from-$to interrupted at ${segment.pos}, retry #$failures: $e")
                delay(RETRY_DELAY * failures)
            } finally {
                activeCalls -= call
            }
        }
    }

    private fun readBody(response: Response, segment: Segment, buffer: ByteArray) {
        val source = response.body.byteStream()
        while (true) {
            val limit = synchronized(lock) { segment.remaining }
            if (limit <= 0) return
            val len = source.read(buffer, 0, minOf(buffer.size.toLong(), limit).toInt())
            if (len == -1) {
                throw EOFException("Segment ended early at ${segment.pos}, end=${segment.end}")
            }
            val position = synchronized(lock) { segment.pos }
            writeFully(buffer, len, position)
            synchronized(lock) { segment.pos += len }
        }
    }

    private fun writeFully(buffer: ByteArray, length: Int, position: Long) {
        val byteBuffer = ByteBuffer.wrap(buffer, 0, length)
        var offset = position
        while (byteBuffer.hasRemaining()) {
            offset += channel.write(byteBuffer, offset)
        }
    }

    private suspend fun onLinkRejected(rejectedUrl: String, code: Int) {
        urlMutex.withLock {
            // 其他连接已经换过链接了，直接用新的重试
            if (currentUrl != rejectedUrl) return
            if (urlRefreshed) throw LinkExpiredException(code)
            urlRefreshed = true
            currentUrl = refreshUrl() ?: throw LinkExpiredException(code)
        }
    }

    private suspend fun save() {
        val (length, encoded) = synchronized(lock) { downloadedLengthLocked() to encodeLocked() }
        // 先落盘再记进度，断电后记录的进度不会超过实际写入的数据
        runCatching { channel.force(false) }
            .onFailure { Log.w(TAG, "force failed: $it") }
        onSave(length, encoded)
    }

    private fun downloadedLengthLocked(): Long =
        segments.sumOf { minOf(it.pos, it.end + 1) - it.start }

    private fun encode(): String = synchronized(lock) { encodeLocked() }

    private fun encodeLocked(): String =
        segments.joinToString(";") { "${it.start},${it.end},${it.pos}" }

    private fun decode(raw: String): List<Segment>? = runCatching {
        val list = raw.split(';').filter { it.isNotBlank() }.map { part ->
            val (start, end, pos) = part.split(',').map { it.trim().toLong() }
            Segment(start, end, pos)
        }.sortedBy { it.start }
        // 各段必须首尾相接、正好覆盖整个文件
        var expectedStart = 0L
        for (segment in list) {
            require(segment.start == expectedStart && segment.end >= segment.start)
            require(segment.pos in segment.start..segment.end + 1)
            expectedStart = segment.end + 1
        }
        require(expectedStart == totalLength)
        list
    }.getOrNull()
}

/**
 * 连接或读流过程中的网络抖动，可以从当前位置续传；
 * 写文件失败、主动取消等不在此列
 */
internal fun IOException.isTransientNetworkError(): Boolean {
    val rawMessage = message.orEmpty()
    return this is SocketTimeoutException ||
            this is SocketException ||
            this is UnknownHostException ||
            this is SSLException ||
            this is ProtocolException ||
            this is EOFException ||
            this is SegmentedDownloader.ServerErrorException ||
            rawMessage.contains("stream was reset", ignoreCase = true) ||
            rawMessage.contains("unexpected end of stream", ignoreCase = true)
}
