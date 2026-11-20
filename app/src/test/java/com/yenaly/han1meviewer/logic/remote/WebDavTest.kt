package com.yenaly.han1meviewer.logic.remote

import com.sun.net.httpserver.HttpsServer
import com.sun.net.httpserver.HttpsConfigurator
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.security.KeyStore
import javax.net.ssl.*
import java.util.concurrent.Executors

class WebDavTest {
    private class Server : AutoCloseable {
        private val keys = KeyStore.getInstance("PKCS12").apply { load(WebDavTest::class.java.getResourceAsStream("/dav-test-only.p12"), "test-only".toCharArray()) }
        private val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "test-only".toCharArray()) }
        private val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        private val ssl = SSLContext.getInstance("TLS").apply { init(km.keyManagers, tm.trustManagers, null) }
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { httpsConfigurator = HttpsConfigurator(ssl); this.executor = this@Server.executor }
        @Volatile var document: ByteArray? = null
        @Volatile var revision = 0
        @Volatile var weak = false
        @Volatile var requests = 0
        val client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, tm.trustManagers.filterIsInstance<X509TrustManager>().single()).build()
        val root get() = "https://127.0.0.1:${server.address.port}/dav/"
        fun dav(password: String = "password") = WebDavClient(root, "user", password, client)
        init {
            server.createContext("/") { exchange ->
                requests++
                if (exchange.requestHeaders.getFirst("Authorization") != Credentials.basic("user", "password")) {
                    exchange.sendResponseHeaders(401, -1); exchange.close()
                } else {
                    val path = exchange.requestURI.path
                    val body: ByteArray
                    val status: Int
                    if (path.endsWith("redirect.mp4")) {
                        exchange.responseHeaders.add("Location", "https://example.invalid/steal")
                        status = 302; body = byteArrayOf()
                    } else when (exchange.requestMethod) {
                        "PROPFIND" -> { status = 207; body = XML.toByteArray() }
                        "GET" -> { status = if (document == null) 404 else 200; body = document ?: byteArrayOf(); exchange.responseHeaders.add("ETag", (if (weak) "W/" else "") + "\"$revision\"") }
                        "PUT" -> {
                            val match = exchange.requestHeaders.getFirst("If-Match")
                            val create = exchange.requestHeaders.getFirst("If-None-Match")
                            if ((document == null && create == "*") || (document != null && match == "\"$revision\"")) {
                                document = exchange.requestBody.readBytes(); revision++; status = 201
                            } else status = 412
                            body = byteArrayOf()
                        }
                        else -> { status = 405; body = byteArrayOf() }
                    }
                    exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
                    exchange.responseBody.use { if (body.isNotEmpty()) it.write(body) }; exchange.close()
                }
            }
            server.start()
        }
        override fun close() { server.stop(0); executor.shutdownNow() }
    }
    @Test fun propfindParsesOnlySuccessfulImmediateChildrenAndRejectsEscapingHrefs(): Unit = Server().use { server ->
        val entries = server.dav().list()
        assertEquals(setOf("Folder", "电影.mp4"), entries.map { it.name }.toSet())
        assertTrue(entries.first().directory)
        assertTrue(entries.first().url.endsWith('/'))
        assertEquals(1024L, entries.last().bytes)
        assertThrows(DavException::class.java) { server.dav("wrong").list() }
    }
    @Test fun conditionalWritesProtectBothInitialCreationAndExistingData(): Unit = Server().use { server ->
        val dav = server.dav()
        assertNull(dav.get("sync"))
        dav.put("sync", "one".toByteArray(), null)
        val old = requireNotNull(dav.get("sync"))
        assertThrows(DavConflictException::class.java) { dav.put("sync", "bad".toByteArray(), null) }
        dav.put("sync", "two".toByteArray(), old.etag)
        assertThrows(DavConflictException::class.java) { dav.put("sync", "bad".toByteArray(), old.etag) }
        assertEquals("two", dav.get("sync")!!.bytes.toString(Charsets.UTF_8))
        server.weak = true
        assertThrows(Exception::class.java) { dav.get("sync") }
    }
    @Test fun credentialsNeverFollowRedirectsAndUrlsCannotEscapeRoot(): Unit = Server().use { server ->
        val wrongProxy = server.client.newBuilder().proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9))).build()
        val dav = WebDavClient(server.root, "user", "password", wrongProxy)
        dav.execute(dav.request(dav.child("redirect.mp4")).build()).use { assertEquals(302, it.code) }
        assertEquals(1, server.requests)
        assertThrows(Exception::class.java) { WebDavClient("http://example.com/", "user", "password") }
        assertThrows(Exception::class.java) { WebDavClient("https://user:password@example.com/", "", "") }
        assertThrows(Exception::class.java) { dav.checkUrl("https://example.com/dav/video.mp4".toHttpUrl()) }
        assertThrows(Exception::class.java) { dav.child("../escape") }
    }
    @Test fun dtdAndOversizedResponsesAreRejectedBeforeUse(): Unit = Server().use { server ->
        val dav = server.dav()
        assertThrows(Exception::class.java) { dav.parseList(dav.root, "<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///private'>]><a/>".toByteArray()) }
        assertThrows(Exception::class.java) { WebDavClient.readBounded(byteArrayOf(1,2,3).inputStream(), 2) }
    }
    companion object {
        private fun response(href: String, name: String, directory: Boolean = false, status: Int = 200) = """<d:response><d:href>$href</d:href><d:propstat><d:prop><d:displayname>$name</d:displayname><d:getcontentlength>1024</d:getcontentlength><d:resourcetype>${if (directory) "<d:collection/>" else ""}</d:resourcetype></d:prop><d:status>HTTP/1.1 $status Status</d:status></d:propstat></d:response>"""
        private val XML = """<d:multistatus xmlns:d="DAV:">""" + response("/dav/", "Root", true) +
            response("/dav/folder/", "Folder", true) + response("/dav/%E7%94%B5%E5%BD%B1.mp4", "电影.mp4") +
            response("https://example.com/dav/stolen.mp4", "Wrong origin") + response("/davish/file.mp4", "Wrong prefix") +
            response("/dav/file%2Fhidden.mp4", "Encoded slash") + response("/dav/%252e%252e/escape.mp4", "Nested encoding") +
            response("/dav/deep/child.mp4", "Grandchild") + response("/dav/denied.mp4", "Denied", status = 403) + "</d:multistatus>"
    }
}
