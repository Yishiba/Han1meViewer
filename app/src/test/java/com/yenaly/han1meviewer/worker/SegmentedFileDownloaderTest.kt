package com.yenaly.han1meviewer.worker

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SegmentedFileDownloaderTest {
    @get:Rule val temp = TemporaryFolder()
    private val client = OkHttpClient()
    private val downloader = SegmentedFileDownloader(client) { request ->
        withContext(Dispatchers.IO) { client.newCall(request).execute() }
    }

    private fun withServer(bytes: ByteArray, ignoreRanges: Boolean = false,
                           truncateOnce: AtomicBoolean? = null,
                           test: (String, List<String>) -> Unit) {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/video") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            if (range != null) requests.add(range)
            if (ignoreRanges || range == null) {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                val match = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range)!!
                val start = match.groupValues[1].toInt()
                val end = match.groupValues[2].toInt()
                val length = end - start + 1
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${bytes.size}")
                exchange.sendResponseHeaders(206, length.toLong())
                exchange.responseBody.use { out ->
                    val actual = if (truncateOnce?.compareAndSet(true, false) == true) length / 2 else length
                    try { out.write(bytes, start, actual) } catch (_: IOException) { }
                }
            }
        }
        server.start()
        try { test("http://127.0.0.1:${server.address.port}/video", requests) }
        finally { server.stop(0); executor.shutdownNow() }
    }

    @Test fun downloadsTwoDisjointRangesAndPreservesExistingPrefix() {
        val bytes = ByteArray(512 * 1024) { (it * 31).toByte() }
        withServer(bytes) { url, requests ->
            RandomAccessFile(temp.newFile(), "rw").use { file ->
                file.setLength(bytes.size.toLong())
                file.write(bytes, 0, 1024)
                var prefix = 1024L
                runBlocking {
                    downloader.download(url, file, prefix, bytes.size.toLong()) { saved, received ->
                        assertTrue(saved >= prefix)
                        assertTrue(received >= saved)
                        prefix = saved
                    }
                }
                assertEquals(bytes.size.toLong(), prefix)
                assertEquals(2, requests.size)
                file.seek(0)
                assertArrayEquals(bytes, ByteArray(bytes.size).also(file::readFully))
            }
        }
    }

    @Test fun unsupportedRangesLeaveCoroutineUsableForSingleConnectionFallback() {
        val bytes = ByteArray(128 * 1024) { it.toByte() }
        withServer(bytes, ignoreRanges = true) { url, _ ->
            RandomAccessFile(temp.newFile(), "rw").use { file ->
                file.setLength(bytes.size.toLong())
                runBlocking {
                    try {
                        downloader.download(url, file, 0, bytes.size.toLong()) { _, _ -> }
                        throw AssertionError("Expected range fallback")
                    } catch (_: SegmentRangeUnsupportedException) {
                        assertTrue(currentCoroutineContext().isActive)
                    }
                    client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                        file.seek(0)
                        val buffer = ByteArray(8192)
                        val stream = response.body.byteStream()
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            file.write(buffer, 0, count)
                        }
                    }
                }
                file.seek(0)
                assertArrayEquals(bytes, ByteArray(bytes.size).also(file::readFully))
            }
        }
    }

    @Test fun interruptedRangeCanResumeFromDurablePrefix() {
        val bytes = ByteArray(384 * 1024) { (it * 17).toByte() }
        val truncate = AtomicBoolean(true)
        withServer(bytes, truncateOnce = truncate) { url, _ ->
            RandomAccessFile(temp.newFile(), "rw").use { file ->
                file.setLength(bytes.size.toLong())
                var prefix = 0L
                runBlocking {
                    try {
                        downloader.download(url, file, 0, bytes.size.toLong()) { saved, _ -> prefix = saved }
                        throw AssertionError("Expected truncated response")
                    } catch (_: IOException) { }
                    assertTrue(prefix < bytes.size)
                    downloader.download(url, file, prefix, bytes.size.toLong()) { saved, _ -> prefix = saved }
                }
                assertEquals(bytes.size.toLong(), prefix)
                file.seek(0)
                assertArrayEquals(bytes, ByteArray(bytes.size).also(file::readFully))
            }
        }
    }
}
