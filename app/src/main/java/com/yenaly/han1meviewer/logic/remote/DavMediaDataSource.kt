package com.yenaly.han1meviewer.logic.remote

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import java.io.IOException
import java.io.InputStream

/** Authenticated range streaming using the same origin/path restrictions as directory browsing. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DavMediaDataSource(private val dav: WebDavClient) : BaseDataSource(true) {
    private var response: Response? = null
    private var input: InputStream? = null
    private var uri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var started = false
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        try {
            val request = dav.request(dataSpec.uri.toString().toHttpUrl())
            if (dataSpec.position > 0 || dataSpec.length != C.LENGTH_UNSET.toLong()) {
                val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) "" else Math.addExact(dataSpec.position, dataSpec.length - 1).toString()
                request.header("Range", "bytes=${dataSpec.position}-$end")
            }
            val result = dav.execute(request.get().build(), streaming = true)
            response = result
            if (result.code !in setOf(200, 206)) throw DavException(result.code)
            val body = result.body ?: throw IOException("Empty media response")
            input = body.byteStream()
            var length = body.contentLength()
            if (result.code == 206) {
                val contentRange = result.header("Content-Range").orEmpty()
                val start = Regex("bytes (\\d+)-\\d+/(?:\\d+|\\*)").matchEntire(contentRange)?.groupValues?.get(1)?.toLongOrNull()
                require(start == dataSpec.position) { "Incorrect media range" }
            } else if (dataSpec.position > 0) {
                var skip = dataSpec.position
                val buffer = ByteArray(65536)
                while (skip > 0) {
                    val count = input!!.read(buffer, 0, minOf(buffer.size.toLong(), skip).toInt())
                    if (count < 0) throw IOException("Seek exceeds file")
                    skip -= count
                }
                if (length >= 0) length -= dataSpec.position
            }
            remaining = when {
                dataSpec.length != C.LENGTH_UNSET.toLong() && length >= 0 -> minOf(dataSpec.length, length)
                dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                else -> length
            }
            uri = dataSpec.uri
            started = true
            transferStarted(dataSpec)
            return remaining
        } catch (failure: Exception) { close(); throw IOException("Unable to open remote media", failure) }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = requireNotNull(input).read(buffer, offset, if (remaining < 0) length else minOf(length.toLong(), remaining).toInt())
        if (count < 0) {
            if (remaining > 0) throw IOException("Truncated media response")
            return C.RESULT_END_OF_INPUT
        }
        if (remaining >= 0) remaining -= count
        bytesTransferred(count)
        return count
    }
    override fun getUri(): Uri? = uri
    override fun getResponseHeaders(): Map<String, List<String>> = response?.headers?.toMultimap().orEmpty()
    override fun close() {
        try { input?.close() } finally {
            response?.close(); response = null; input = null; uri = null
            if (started) { started = false; transferEnded() }
        }
    }
}
