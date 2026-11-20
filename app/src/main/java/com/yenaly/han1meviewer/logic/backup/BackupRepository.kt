package com.yenaly.han1meviewer.logic.backup

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface BackupBackend {
    suspend fun capture(): BackupPayload
    suspend fun apply(payload: BackupPayload)
}

/** Serializes vault operations; journal survives process death between the separate Room transactions. */
class BackupRepository(val vault: BackupVault, private val backend: BackupBackend) {
    private val mutex = Mutex()

    suspend fun create(kind: BackupKind, keep: Int): BackupVersion = mutex.withLock {
        check(!vault.hasJournal()) { "Recovery required" }
        vault.write(backend.capture(), kind).also { vault.prune(keep) }
    }

    suspend fun export(id: String): ByteArray = mutex.withLock { vault.read(id) }
    suspend fun delete(id: String) = mutex.withLock {
        check(!vault.hasJournal()) { "Recovery required" }
        vault.delete(id)
    }
    suspend fun list(): List<BackupVersion> = mutex.withLock { vault.list() }

    suspend fun restoreVersion(id: String, keep: Int) = mutex.withLock { restore(vault.read(id), keep) }
    suspend fun import(bytes: ByteArray, keep: Int) = mutex.withLock { restore(bytes, keep) }

    suspend fun importApplying(bytes: ByteArray, keep: Int, applyReplacement: suspend (BackupPayload) -> Unit) = mutex.withLock {
        restore(bytes, keep, applyReplacement)
    }

    private suspend fun restore(bytes: ByteArray, keep: Int, applyReplacement: suspend (BackupPayload) -> Unit = backend::apply) {
        check(!vault.hasJournal()) { "Recovery required" }
        val payload = BackupCodec.decode(bytes) // All input validation happens before any mutation.
        val safety = vault.write(backend.capture(), BackupKind.BEFORE_RESTORE)
        vault.beginRestore(safety.id)
        withContext(NonCancellable) {
            try {
                applyReplacement(payload)
                vault.endRestore()
            } catch (failure: Exception) {
                try {
                    backend.apply(BackupCodec.decode(vault.read(safety.id)))
                    vault.endRestore()
                } catch (rollback: Exception) { failure.addSuppressed(rollback) }
                throw failure
            }
            vault.prune(keep)
        }
    }

    suspend fun recover(): Boolean = mutex.withLock {
        val id = vault.recoveryId() ?: return@withLock false
        withContext(NonCancellable) {
            backend.apply(BackupCodec.decode(vault.read(id)))
            vault.endRestore()
        }
        true
    }
}
