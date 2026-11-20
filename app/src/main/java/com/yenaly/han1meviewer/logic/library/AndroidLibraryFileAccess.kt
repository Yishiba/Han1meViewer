package com.yenaly.han1meviewer.logic.library

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Never treats a failed document-provider access as proof that the user's file was deleted. */
class AndroidLibraryFileAccess(private val context: Context) : LibraryFileAccess {
    override fun referenceKey(uri: String): String = runCatching {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "file" || parsed.scheme == null) file(parsed, uri).canonicalPath
        else if (DocumentsContract.isDocumentUri(context, parsed))
            "document:${parsed.authority}:${DocumentsContract.getDocumentId(parsed)}"
        else parsed.normalizeScheme().toString()
    }.getOrDefault(uri)

    override suspend fun probe(uri: String): FileProbe {
        currentCoroutineContext().ensureActive()
        return try {
            val parsed = Uri.parse(uri)
            when (parsed.scheme) {
                "file", null -> {
                    val file = file(parsed, uri).canonicalFile
                    if (file.path.startsWith("/storage/") && Environment.getExternalStorageState(file) !in
                        setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)) {
                        FileProbe(FileStatus.UNAVAILABLE)
                    } else {
                        try {
                            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
                            if (!attributes.isRegularFile) FileProbe(FileStatus.UNAVAILABLE)
                            else FileInputStream(file).use {
                                FileProbe(FileStatus.AVAILABLE, attributes.size(), file.path, attributes.lastModifiedTime().toMillis())
                            }
                        } catch (_: NoSuchFileException) { FileProbe(FileStatus.MISSING, identity = file.path) }
                    }
                }
                "content" -> context.contentResolver.openFileDescriptor(parsed, "r")?.use {
                    FileProbe(FileStatus.AVAILABLE, it.statSize, referenceKey(uri))
                } ?: FileProbe(FileStatus.UNAVAILABLE)
                else -> FileProbe(FileStatus.UNAVAILABLE)
            }
        } catch (_: Exception) { FileProbe(FileStatus.UNAVAILABLE) }
    }

    override suspend fun sha256(uri: String, expectedBytes: Long): String {
        val parsed = Uri.parse(uri)
        val stream: InputStream = when (parsed.scheme) {
            "file", null -> FileInputStream(file(parsed, uri))
            "content" -> requireNotNull(context.contentResolver.openInputStream(parsed))
            else -> error("Unsupported media URI")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        stream.use {
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = it.read(buffer)
                if (count < 0) break
                bytes += count
                check(bytes <= expectedBytes) { "File changed while hashing" }
                digest.update(buffer, 0, count)
            }
        }
        check(bytes == expectedBytes) { "File changed while hashing" }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun file(uri: Uri, raw: String) = File(if (uri.scheme == "file") requireNotNull(uri.path) else raw)
}
