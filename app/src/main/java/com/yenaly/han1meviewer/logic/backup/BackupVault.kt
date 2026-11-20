package com.yenaly.han1meviewer.logic.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
enum class BackupKind { MANUAL, AUTOMATIC, BEFORE_RESTORE }

@Serializable
data class BackupVersion(
    val id: String,
    val createdAt: Long,
    val kind: BackupKind,
    val bytes: Int,
    val sha256: String,
    val downloads: Int,
    val histories: Int,
    val rules: Int,
)

/** Each version is one atomically published file: bounded metadata line + verified JSON payload. */
class BackupVault(private val directory: File) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private fun file(id: String): File {
        require(idPattern.matches(id)) { "Invalid version identifier" }
        return File(directory, "$id.hvb")
    }

    fun list(): List<BackupVersion> = directory.listFiles().orEmpty().mapNotNull { candidate ->
        val id = candidate.name.removeSuffix(".hvb")
        if (candidate.extension != "hvb" || !idPattern.matches(id)) return@mapNotNull null
        runCatching { header(candidate).also { require(it.id == id) } }.getOrNull()
    }.sortedWith(compareByDescending<BackupVersion> { it.createdAt }.thenByDescending { it.id })

    fun write(payload: BackupPayload, kind: BackupKind, now: Long = System.currentTimeMillis()): BackupVersion {
        val bytes = BackupCodec.encode(payload)
        val rules = (payload.settings?.get(com.yenaly.han1meviewer.logic.library.SmartCollectionStore.KEY)
            as? PreferenceValue.StringValue)?.let {
            runCatching { com.yenaly.han1meviewer.logic.library.MediaLibraryCodec.decodeCollections(it.value).size }
                .getOrDefault(0)
        } ?: 0
        val createdAt = maxOf(now, list().firstOrNull()?.createdAt?.plus(1) ?: now)
        val version = BackupVersion(UUID.randomUUID().toString(), createdAt, kind, bytes.size,
            BackupCodec.digest(bytes), payload.downloads.orEmpty().size, payload.watchHistories.orEmpty().size, rules)
        atomicWrite(file(version.id), json.encodeToString(version).toByteArray() + byteArrayOf(10) + bytes)
        // Do not prune older versions unless the new file can be read and validated.
        read(version.id)
        return version
    }

    fun read(id: String): ByteArray {
        val target = file(id)
        require(target.length() <= BackupCodec.MAX_BYTES + 8193L) { "Invalid version size" }
        target.inputStream().buffered().use { stream ->
            val version = json.decodeFromString<BackupVersion>(readHeader(stream))
            require(version.id == id && version.bytes in 1..BackupCodec.MAX_BYTES)
            val bytes = BackupCodec.readBounded(stream, version.bytes)
            require(bytes.size == version.bytes && BackupCodec.digest(bytes) == version.sha256) { "Damaged version" }
            BackupCodec.decode(bytes)
            return bytes
        }
    }

    fun delete(id: String) {
        val target = file(id)
        require(header(target).kind != BackupKind.BEFORE_RESTORE) { "Protected recovery version" }
        check(target.delete()) { "Unable to remove version" }
    }

    fun prune(keep: Int, protectedSafetyId: String? = null) {
        require(keep in setOf(3, 7, 14, 30))
        val all = list()
        all.filter { it.kind != BackupKind.BEFORE_RESTORE }.drop(keep).forEach { file(it.id).delete() }
        // The latest safety snapshot survives retention as well as any outstanding recovery journal.
        all.filter { it.kind == BackupKind.BEFORE_RESTORE }.drop(1)
            .filter { it.id != protectedSafetyId }.forEach { file(it.id).delete() }
    }

    private val journal get() = File(directory, "restore-journal")
    fun recoveryId(): String? = if (journal.exists()) journal.readText().trim().also { file(it) } else null
    fun hasJournal(): Boolean = journal.exists()
    fun beginRestore(id: String) { read(id); atomicWrite(journal, id.toByteArray()) }
    fun endRestore() { check(!journal.exists() || journal.delete()) { "Unable to clear recovery journal" } }

    private fun header(file: File): BackupVersion = file.inputStream().buffered().use {
        json.decodeFromString<BackupVersion>(readHeader(it))
    }

    private fun readHeader(stream: java.io.InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        repeat(8192) {
            val next = stream.read()
            require(next >= 0) { "Missing version header" }
            if (next == 10) return bytes.toString(Charsets.UTF_8.name())
            bytes.write(next)
        }
        error("Version header too large")
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create backup directory" }
        val partial = File(directory, "${target.name}.${UUID.randomUUID()}.partial")
        try {
            FileOutputStream(partial).use { it.write(bytes); it.fd.sync() }
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
            // Best effort directory fsync is platform-specific; file contents are fsynced before rename.
        } finally { partial.delete() }
    }
}
