package com.yenaly.han1meviewer.logic.remote

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class DavException(val status: Int) : IOException("WebDAV HTTP $status")
class DavConflictException : IOException("Remote document changed; preview again")
data class DavEntry(val url: String, val name: String, val directory: Boolean, val bytes: Long)
data class DavDocument(val bytes: ByteArray, val etag: String)

/** Dedicated client: no site cookies, interceptors, redirects or credential-bearing URLs. */
class WebDavClient(
    root: String, private val username: String, private val password: String,
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build(),
) {
    val root: HttpUrl = root.toHttpUrl().let { if (it.encodedPath.endsWith('/')) it else it.newBuilder().addPathSegment("").build() }
    init {
        require(root.length in 1..4000 && username.length <= 500 && password.length <= 4096 && !username.contains(':'))
        require(this.root.scheme == "https")
        require(this.root.username.isEmpty() && this.root.password.isEmpty() && this.root.query == null && this.root.fragment == null)
        checkUrl(this.root)
    }
    fun checkUrl(url: HttpUrl): HttpUrl {
        require(url.scheme == root.scheme && url.host == root.host && url.port == root.port)
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
        require(url.encodedPath.startsWith(root.encodedPath))
        require(url.pathSegments.all { it != "." && it != ".." && it.none { c -> c == '/' || c == '\\' || c == '%' || c.code < 32 } })
        return url
    }
    fun child(name: String): HttpUrl {
        require(name.isNotBlank() && name.none { it == '/' || it == '\\' || it == '%' || it.code < 32 } && name != "." && name != "..")
        return root.newBuilder().addPathSegment(name).build()
    }
    fun request(url: HttpUrl): Request.Builder = Request.Builder().url(checkUrl(url))
        .header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))
        .header("Accept-Encoding", "identity")
    fun execute(request: Request, streaming: Boolean = false): Response {
        checkUrl(request.url)
        // Always disable redirects even when a test/injected client has different defaults.
        return client.newBuilder().proxy(java.net.Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .callTimeout(if (streaming) 0 else 60, TimeUnit.SECONDS).build().newCall(request).execute()
    }
    fun list(url: String = root.toString()): List<DavEntry> {
        val directory = checkUrl(url.toHttpUrl().let { if (it.encodedPath.endsWith('/')) it else it.newBuilder().addPathSegment("").build() })
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""
        return execute(request(directory).header("Depth", "1").method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType())).build()).use {
            if (it.code != 207) throw DavException(it.code)
            parseList(directory, readBounded(requireNotNull(it.body).byteStream(), 2 * 1024 * 1024))
        }
    }
    fun get(name: String): DavDocument? = execute(request(child(name)).get().build()).use {
        if (it.code == 404) return null
        if (it.code != 200) throw DavException(it.code)
        val etag = it.header("ETag").orEmpty()
        require(etag.length in 2..512 && etag.startsWith('"') && etag.endsWith('"') && etag.substring(1, etag.lastIndex).all { c -> c != '"' && c.code in 0x21..0xff }) { "Strong ETag required for sync" }
        DavDocument(readBounded(requireNotNull(it.body).byteStream(), 12 * 1024 * 1024), etag)
    }
    fun put(name: String, bytes: ByteArray, etag: String?) {
        val request = request(child(name)).header(if (etag == null) "If-None-Match" else "If-Match", etag ?: "*")
            .put(bytes.toRequestBody("application/octet-stream".toMediaType())).build()
        execute(request).use {
            if (it.code == 412) throw DavConflictException()
            if (it.code !in setOf(200, 201, 204)) throw DavException(it.code)
        }
    }
    internal fun parseList(directory: HttpUrl, bytes: ByteArray): List<DavEntry> {
        // No DTD/entity processing, including on Android parsers that do not expose JAXP attributes.
        val xml = bytes.toString(Charsets.UTF_8)
        require(!xml.contains('\u0000') && !xml.contains('\ufffd')) { "WebDAV XML must be UTF-8" }
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true))
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // Android Harmony does not implement these optional flags. The mandatory guards are
            // UTF-8-only character input, the DTD/entity ban above, and the refusing resolver below.
            for (feature in listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities")) {
                try { setFeature(feature, false) } catch (_: javax.xml.parsers.ParserConfigurationException) { }
            }
        }
        val document = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw IOException("External entity refused") }
        }.parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
        require(document.documentElement.localName == "multistatus" && document.documentElement.namespaceURI == "DAV:")
        val nodes = document.getElementsByTagNameNS("DAV:", "response")
        require(nodes.length <= 10000)
        return (0 until nodes.length).mapNotNull { index ->
            val response = nodes.item(index) as Element
            fun Element.text(name: String): String? = getElementsByTagNameNS("DAV:", name).item(0)?.textContent
            val href = response.text("href") ?: return@mapNotNull null
            val resolved = directory.resolve(href) ?: return@mapNotNull null
            if (resolved.toString().length > 4000 || runCatching { checkUrl(resolved) }.isFailure || resolved.encodedPath.trimEnd('/') == directory.encodedPath.trimEnd('/')) return@mapNotNull null
            val relative = resolved.encodedPath.removePrefix(directory.encodedPath)
            if (!resolved.encodedPath.startsWith(directory.encodedPath) || relative.trimEnd('/').contains('/')) return@mapNotNull null
            val props = response.getElementsByTagNameNS("DAV:", "propstat")
            val good = (0 until props.length).map { props.item(it) as Element }.firstOrNull {
                it.text("status")?.matches(Regex("HTTP/\\S+ 200(?: .*)?")) == true
            } ?: return@mapNotNull null
            val folder = good.getElementsByTagNameNS("DAV:", "collection").length > 0
            val name = good.text("displayname")?.take(500)?.takeIf { it.isNotBlank() }
                ?: resolved.pathSegments.lastOrNull { it.isNotEmpty() }.orEmpty()
            DavEntry((if (folder && !resolved.encodedPath.endsWith('/')) resolved.newBuilder().addPathSegment("").build() else resolved).toString(), name, folder, good.text("getcontentlength")?.toLongOrNull()?.coerceAtLeast(0) ?: -1)
        }.distinctBy { it.url }.sortedWith(compareByDescending<DavEntry> { it.directory }.thenBy { it.name.lowercase() })
    }
    companion object {
        fun readBounded(input: InputStream, limit: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16384)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(output.size().toLong() + n <= limit) { "Remote response too large" }
                output.write(buffer, 0, n)
            }
            return output.toByteArray()
        }
        fun playable(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m3u8")
    }
}
