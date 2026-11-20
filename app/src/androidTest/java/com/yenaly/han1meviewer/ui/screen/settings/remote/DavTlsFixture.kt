package com.yenaly.han1meviewer.ui.screen.settings.remote

import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.*
import java.io.*
import java.net.InetAddress
import java.security.KeyStore
import javax.net.ssl.*
import java.util.concurrent.Executors

/** Synthetic HTTPS WebDAV server with an isolated test-only certificate, never installed as a system CA. */
class DavTlsFixture : AutoCloseable {
    private val keys = KeyStore.getInstance("PKCS12").apply {
        InstrumentationRegistry.getInstrumentation().context.assets.open("dav-test-only.p12").use { load(it, "test-only".toCharArray()) }
    }
    private val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "test-only".toCharArray()) }
    private val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
    private val ssl = SSLContext.getInstance("TLS").apply { init(km.keyManagers, tm.trustManagers, null) }
    private val server = ssl.serverSocketFactory.createServerSocket(0, 10, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
    private val executor = Executors.newSingleThreadExecutor()
    val client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, tm.trustManagers.filterIsInstance<X509TrustManager>().single()).build()
    val root get() = "https://127.0.0.1:${server.localPort}/dav/"
    val media = ByteArray(1024) { (it % 256).toByte() }
    private val video = InstrumentationRegistry.getInstrumentation().context.assets.open("dav-playback-test.mp4").use { it.readBytes() }
    @Volatile var directoryXml = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/sample.mp4</d:href><d:propstat><d:prop><d:displayname>sample.mp4</d:displayname><d:getcontentlength>1024</d:getcontentlength><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""".toByteArray()
    @Volatile var document: ByteArray? = null
    @Volatile var revision = 0
    @Volatile var getStatus: Int? = null
    @Volatile var requests = 0
    init {
        executor.submit {
            while (!server.isClosed) {
                try { (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 10000
                    val input = socket.inputStream.buffered()
                    fun line(): String {
                        val result = ByteArrayOutputStream()
                        while (result.size() < 8192) {
                            val byte = input.read()
                            if (byte < 0) throw EOFException()
                            if (byte == 10) return result.toString(Charsets.UTF_8.name()).trimEnd('\r')
                            result.write(byte)
                        }
                        error("Header too large")
                    }
                    val request = line().split(' ')
                    val headers = mutableMapOf<String, String>()
                    while (true) { val next = line(); if (next.isEmpty()) break; headers[next.substringBefore(':').lowercase()] = next.substringAfter(':').trim() }
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    require(length in 0..12 * 1024 * 1024)
                    val body = ByteArray(length)
                    var offset = 0
                    while (offset < length) { val n = input.read(body, offset, length - offset); if (n < 0) throw EOFException(); offset += n }
                    requests++
                    var status = 200
                    var output = byteArrayOf()
                    val extra = mutableMapOf<String, String>()
                    if (headers["authorization"] != Credentials.basic("user", "password")) status = 401
                    else if (request[1].endsWith("redirect.mp4")) { status = 302; extra["Location"] = "https://example.invalid/credential-leak" }
                    else if (request[1].endsWith("sample.mp4") || request[1].endsWith("playback.mp4")) {
                        val content = if (request[1].endsWith("playback.mp4")) video else media
                        val range = headers["range"]?.removePrefix("bytes=")
                        val start = range?.substringBefore('-')?.toInt() ?: 0
                        val end = range?.substringAfter('-')?.toIntOrNull() ?: content.lastIndex
                        output = content.copyOfRange(start, end + 1)
                        if (range != null) { status = 206; extra["Content-Range"] = "bytes $start-$end/${content.size}" }
                    } else when (request[0]) {
                        "PROPFIND" -> {
                            status = 207
                            output = directoryXml
                        }
                        "GET" -> { output = document ?: byteArrayOf(); status = getStatus ?: if (document == null) 404 else 200; extra["ETag"] = "\"$revision\"" }
                        "PUT" -> {
                            if ((document == null && headers["if-none-match"] == "*") || (document != null && headers["if-match"] == "\"$revision\"")) {
                                document = body; revision++; status = 201
                            } else status = 412
                        }
                        else -> status = 405
                    }
                    val response = "HTTP/1.1 $status Status\r\nContent-Length: ${output.size}\r\nConnection: close\r\n" + extra.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n"
                    socket.outputStream.write(response.toByteArray()); socket.outputStream.write(output); socket.outputStream.flush()
                } } catch (failure: Exception) {
                    if (server.isClosed) break
                    android.util.Log.e("RemoteTestTLS", "Fixture connection failed", failure)
                }
            }
        }
    }
    override fun close() { server.close(); executor.shutdownNow() }
}
