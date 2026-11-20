package com.yenaly.han1meviewer.logic.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import com.yenaly.han1meviewer.logic.dao.DownloadDatabase
import com.yenaly.han1meviewer.logic.state.DownloadState
import com.yenaly.han1meviewer.util.await
import com.yenaly.han1meviewer.worker.HanimeDownloadWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.TimeUnit

data class BackupUiState(val settings: BackupSettings = BackupSettings(),
                         val versions: List<BackupVersion> = emptyList(), val loaded: Boolean = false)

class BackupService private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: BackupService? = null
        fun get(context: Context): BackupService = instance ?: synchronized(this) {
            instance ?: BackupService(context.applicationContext).also { instance = it }
        }
        const val WORK_NAME = "local-versioned-backup"
    }

    private val store = BackupSettingsStore(context)
    private val passwords = BackupPasswordStore(context)
    private val vault = BackupVault(File(context.noBackupFilesDir, "backup-versions"))
    private val repository by lazy { BackupRepository(vault, AndroidBackupBackend(context)) }
    private val operations = Mutex()
    private val gate = BackupAccessGate()
    private val mutableState = MutableStateFlow(BackupUiState())
    val state = mutableState.asStateFlow()
    private val recovery = MutableStateFlow(vault.hasJournal())
    val recoveryRequired = recovery.asStateFlow()

    suspend fun recoverOnStartup() = withContext(Dispatchers.IO) {
        operations.withLock {
            try { if (vault.hasJournal()) gate.restore { repository.recover() } }
            finally { recovery.value = vault.hasJournal() }
        }
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        operations.withLock {
            try { if (vault.hasJournal()) gate.restore { repository.recover() } }
            finally { recovery.value = vault.hasJournal() }
            refresh()
            schedule(store.read())
        }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutableState.value = BackupUiState(store.read(), repository.list(), loaded = true)
    }

    suspend fun configure(config: BackupSettings) = withContext(Dispatchers.IO) {
        operations.withLock {
            check(!recovery.value) { "Recovery required" }
            val old = store.read()
            val updated = config.copy(lastSuccess = old.lastSuccess, lastFailure = old.lastFailure,
                mirrorFailed = if (old.mirrorUri == config.mirrorUri) old.mirrorFailed else false)
            store.save(updated)
            schedule(updated)
            vault.prune(updated.keep)
            refresh()
        }
    }

    private suspend fun schedule(config: BackupSettings) {
        val manager = WorkManager.getInstance(context)
        if (!config.enabled) manager.cancelUniqueWork(WORK_NAME).await()
        else manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AutomaticBackupWorker>(config.days.toLong(), TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .addTag(WORK_NAME).build()).await()
    }

    suspend fun create(automatic: Boolean = false): BackupVersion? = withContext(Dispatchers.IO) {
        operations.withLock {
            val config = store.read()
            if (automatic && !config.enabled) return@withLock null
            try {
                val version = repository.create(if (automatic) BackupKind.AUTOMATIC else BackupKind.MANUAL, config.keep)
                val mirrorFailed = config.mirrorUri?.let { uri ->
                    try { mirror(version, Uri.parse(uri)); false }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { true }
                } ?: false
                store.save(config.copy(lastSuccess = version.createdAt, lastFailure = false, mirrorFailed = mirrorFailed))
                version
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { store.save(config.copy(lastFailure = true)); throw failure }
            finally { withContext(NonCancellable) { refresh() } }
        }
    }

    suspend fun configureCopyPassword(password: String?) = withContext(Dispatchers.IO) {
        operations.withLock {
            check(!recovery.value)
            if (password != null) { passwords.save(password); store.save(store.read().copy(encryptedMirror = true)) }
            else { store.save(store.read().copy(encryptedMirror = false)); passwords.clear() }
            refresh()
        }
    }

    suspend fun prepareExport(id: String? = null, password: String? = null): ByteArray = withContext(Dispatchers.IO) {
        password?.let(EncryptedBackupCodec::validatePassword)
        operations.withLock {
            val versionId = id ?: repository.create(BackupKind.MANUAL, store.read().keep).also {
                store.save(store.read().copy(lastSuccess = it.createdAt, lastFailure = false))
                refresh()
            }.id
            val bytes = repository.export(versionId)
            if (password == null) bytes else try { EncryptedBackupCodec.encrypt(bytes, password) } finally { bytes.fill(0) }
        }
    }
    suspend fun writeExport(bytes: ByteArray, uri: Uri) = withContext(Dispatchers.IO) {
        require(bytes.size in 1..EncryptedBackupCodec.MAX_FILE_BYTES)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: error("Unable to open destination")
    }
    suspend fun export(id: String, uri: Uri, password: String? = null) = writeExport(prepareExport(id, password), uri)
    suspend fun exportCurrent(uri: Uri, password: String? = null) = writeExport(prepareExport(password = password), uri)

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        operations.withLock { repository.delete(id); refresh() }
    }

    suspend fun applySynchronizedMetadata(expected: BackupPayload, replacement: BackupPayload, publish: suspend () -> Unit) = restoreOperation {
        val current = AndroidBackupBackend(context).capture().copy(exportedAt = 0)
        if (current != expected.copy(exportedAt = 0)) throw com.yenaly.han1meviewer.logic.remote.SyncLocalChangedException()
        val bytes = BackupCodec.encode(replacement)
        publish()
        if (AndroidBackupBackend(context).capture().copy(exportedAt = 0) != current) throw com.yenaly.han1meviewer.logic.remote.SyncLocalChangedException()
        if (current != replacement.copy(exportedAt = 0))
            repository.importApplying(bytes, store.read().keep) { AndroidBackupBackend(context).applySynchronized(it) }
    }

    suspend fun restore(id: String) = restoreOperation { repository.restoreVersion(id, store.read().keep) }
    suspend fun import(uri: Uri, password: String? = null) = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri)?.use { BackupCodec.readBounded(it, EncryptedBackupCodec.MAX_FILE_BYTES) }
            ?: error("Unable to open backup")
        val bytes = EncryptedBackupCodec.decode(input, password)
        try { restoreOperation { repository.import(bytes, store.read().keep) } } finally { bytes.fill(0); input.fill(0) }
    }

    private suspend fun restoreOperation(action: suspend () -> Unit) = withContext(Dispatchers.IO) {
        operations.withLock {
            try {
                gate.restore {
                    check(!recovery.value) { "Recovery required" }
                    val pendingWork = WorkManager.getInstance(context).getWorkInfosByTag(HanimeDownloadWorker.TAG).await()
                        .any { !it.state.isFinished }
                    val pendingRecords = DownloadDatabase.instance.hanimeDownloadDao.getAll()
                        .any { it.state == DownloadState.Downloading || it.state == DownloadState.Queued }
                    if (pendingWork || pendingRecords ||
                        com.yenaly.han1meviewer.worker.HanimeDownloadManagerV2.hasPendingTasks) throw BackupBusyException()
                    action()
                }
            } finally { withContext(NonCancellable) { recovery.value = vault.hasJournal(); refresh() } }
        }
    }

    suspend fun <T> withDownloadAccess(action: suspend () -> T): T = gate.download {
        check(!recovery.value) { "Recovery required" }
        action()
    }

    suspend fun <T> withSearchToolsAccess(action: suspend () -> T): T = withDownloadAccess(action)

    private suspend fun mirror(version: BackupVersion, uri: Uri) {
        val root = DocumentFile.fromTreeUri(context, uri) ?: error("Directory unavailable")
        check(root.isDirectory && root.canWrite())
        val raw = repository.export(version.id)
        val encrypted = store.read().encryptedMirror
        val bytes = if (encrypted) EncryptedBackupCodec.encrypt(raw, passwords.read()) else raw
        val name = "Han1meViewer-${version.createdAt}-${version.id}." + if (encrypted) "hvbenc" else "json"
        val document = root.createFile("application/octet-stream", "$name.partial") ?: error("Cannot create copy")
        try {
            context.contentResolver.openOutputStream(document.uri, "wt")?.use { it.write(bytes) }
                ?: error("Cannot write copy")
            context.contentResolver.openInputStream(document.uri)?.use {
                check(BackupCodec.digest(BackupCodec.readBounded(it, EncryptedBackupCodec.MAX_FILE_BYTES)) == BackupCodec.digest(bytes))
            } ?: error("Cannot verify copy")
            check(document.renameTo(name)) { "Cannot finalize copy" }
        } catch (failure: Exception) { document.delete(); throw failure }
    }
}

class AutomaticBackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        BackupService.get(applicationContext).create(automatic = true)
        Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
}
