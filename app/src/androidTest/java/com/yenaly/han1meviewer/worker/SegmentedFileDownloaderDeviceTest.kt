package com.yenaly.han1meviewer.worker

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class SegmentedFileDownloaderDeviceTest {
    @Test fun writesTwoRangesToAnAndroidFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = ByteArray(512 * 1024) { (it * 13).toByte() }
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val handler = thread(name = "download-range-test") {
            repeat(2) {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                    input.readLine()
                    var range: String? = null
                    while (true) {
                        val line = input.readLine()
                        if (line.isNullOrEmpty()) break
                        if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                    }
                    val match = Regex("bytes=(\\d+)-(\\d+)").matchEntire(requireNotNull(range))!!
                    val start = match.groupValues[1].toInt()
                    val end = match.groupValues[2].toInt()
                    val header = "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Range: bytes $start-$end/${bytes.size}\r\n" +
                        "Content-Length: ${end - start + 1}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().use { out ->
                        out.write(header.toByteArray(Charsets.US_ASCII))
                        out.write(bytes, start, end - start + 1)
                        out.flush()
                    }
                }
            }
        }
        val file = File.createTempFile("range-download-", ".bin", context.cacheDir)
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(bytes.size.toLong())
                var prefix = 0L
                runBlocking {
                    SegmentedFileDownloader(OkHttpClient()).download(
                        "http://127.0.0.1:${server.localPort}/video", raf, 0, bytes.size.toLong()
                    ) { saved, _ -> prefix = saved }
                }
                assertEquals(bytes.size.toLong(), prefix)
                raf.seek(0)
                assertArrayEquals(bytes, ByteArray(bytes.size).also(raf::readFully))
            }
        } finally {
            server.close()
            handler.join(1_000)
            file.delete()
        }
    }
}
