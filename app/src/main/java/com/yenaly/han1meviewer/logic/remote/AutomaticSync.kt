package com.yenaly.han1meviewer.logic.remote

import android.content.Context
import androidx.work.*
import com.yenaly.han1meviewer.logic.backup.BackupBusyException
import com.yenaly.han1meviewer.util.await
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class AutomaticSyncStopped : IllegalStateException("Automatic sync configuration changed")

class AutomaticSyncScheduler(private val context: Context,
    private val settings: AutomaticSyncSettingsStore = AutomaticSyncSettingsStore(context),
    private val remote: RemoteStore = RemoteStore(context)) {
    companion object { const val WORK_NAME = "remote-automatic-sync"; const val GENERATION = "configuration-generation"; private val mutex = Mutex() }
    suspend fun initialize() = withContext(Dispatchers.IO) { mutex.withLock { schedule(settings.read()) } }
    suspend fun configure(enabled: Boolean, minutes: Int, unmetered: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (enabled) remote.read().profile.let { it.validate(); require(it.syncPassphrase.length in 12..256) }
            val config = settings.update { it.copy(enabled = enabled, minutes = minutes, unmetered = unmetered,
                generation = UUID.randomUUID().toString(), status = AutomaticSyncStatus.IDLE) }
            schedule(config)
        }
    }
    private suspend fun schedule(config: AutomaticSyncSettings) {
        val manager = WorkManager.getInstance(context)
        if (!config.enabled) manager.cancelUniqueWork(WORK_NAME).await()
        else manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AutomaticSyncWorker>(config.minutes.toLong(), TimeUnit.MINUTES)
                .setInitialDelay(config.minutes.toLong(), TimeUnit.MINUTES)
                .setInputData(workDataOf(GENERATION to config.generation))
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true)
                    .setRequiredNetworkType(if (config.unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(WORK_NAME).build()).await()
    }
    suspend fun manualSuccess() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val config = settings.update { it.copy(status = AutomaticSyncStatus.SUCCESS,
                lastSuccess = System.currentTimeMillis(), generation = UUID.randomUUID().toString()) }
            schedule(config)
        }
    }
}

enum class AutomaticRunResult { COMPLETE, RETRY }

/** Shared by WorkManager and integration tests; persisted status contains no private error strings. */
class AutomaticSyncRunner(private val settings: AutomaticSyncSettingsStore,
    private val service: RemoteSyncService) {
    suspend fun run(generation: String?, attempt: Int = 0): AutomaticRunResult {
        val config = settings.read()
        if (!config.enabled || config.blocked || generation != config.generation) return AutomaticRunResult.COMPLETE
        fun guard() {
            val current = settings.read()
            if (!current.enabled || current.blocked || current.generation != generation) throw AutomaticSyncStopped()
        }
        var retry = false
        val status = try {
            val result = service.automatic(::guard)
            if (result == AutomaticSyncOutcome.CONFLICT) AutomaticSyncStatus.CONFLICT else AutomaticSyncStatus.SUCCESS
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: AutomaticSyncStopped) { return AutomaticRunResult.COMPLETE }
        catch (_: BackupBusyException) { retry = true; AutomaticSyncStatus.BUSY }
        catch (_: SyncLocalChangedException) { retry = true; AutomaticSyncStatus.LOCAL_CHANGED }
        catch (_: DavConflictException) { retry = true; AutomaticSyncStatus.RETRY }
        catch (error: DavException) {
            if (error.status in setOf(401, 403)) AutomaticSyncStatus.AUTH
            else if (error.status == 429 || error.status >= 500) { retry = true; AutomaticSyncStatus.RETRY }
            else AutomaticSyncStatus.INVALID
        }
        catch (_: javax.net.ssl.SSLException) { AutomaticSyncStatus.INVALID }
        catch (_: IOException) { retry = true; AutomaticSyncStatus.RETRY }
        catch (_: Exception) { AutomaticSyncStatus.INVALID }
        settings.update { current ->
            if (current.generation != generation || !current.enabled) current
            else current.copy(status = status, lastAttempt = System.currentTimeMillis(),
                lastSuccess = if (status == AutomaticSyncStatus.SUCCESS) System.currentTimeMillis() else current.lastSuccess)
        }
        return if (retry && attempt < 3) AutomaticRunResult.RETRY else AutomaticRunResult.COMPLETE
    }
}

class AutomaticSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        return when (AutomaticSyncRunner(AutomaticSyncSettingsStore(applicationContext), RemoteSyncService(applicationContext))
            .run(inputData.getString(AutomaticSyncScheduler.GENERATION), runAttemptCount)) {
            AutomaticRunResult.COMPLETE -> Result.success()
            AutomaticRunResult.RETRY -> Result.retry()
        }
    }
}
