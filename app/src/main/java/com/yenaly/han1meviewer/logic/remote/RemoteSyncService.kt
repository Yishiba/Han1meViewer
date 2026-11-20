package com.yenaly.han1meviewer.logic.remote

import android.content.Context
import com.yenaly.han1meviewer.logic.backup.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SyncLocalChangedException : IllegalArgumentException("Local data changed; preview again")
enum class AutomaticSyncOutcome { SYNCED, UNCHANGED, CONFLICT }
data class RemoteSyncPlan(
    val local: RemoteLocal, val snapshot: BackupPayload, val remote: DavDocument?,
    val merged: SyncMerge, val materialized: SyncMaterialized,
    val remoteDocument: SyncDocument = SyncDocument(),
) {
    val live get() = SyncMergeEngine.live(merged.document)
    val changedKeys get() = (live.keys + local.baseline.local.keys).filter { live[it] != local.baseline.local[it] }
}
class RemoteSyncService(
    context: Context,
    private val store: RemoteStore = RemoteStore(context),
    private val backend: BackupBackend = AndroidBackupBackend(context),
    private val clientFactory: (DavProfile) -> WebDavClient = { it.client() },
    private val applyMetadata: suspend (BackupPayload, BackupPayload, suspend () -> Unit) -> Unit = { expected, replacement, publish ->
        BackupService.get(context).applySynchronizedMetadata(expected, replacement, publish)
    },
) {
    companion object { const val FILE = ".han1me-sync-v1.json"; private val mutex = Mutex() }
    suspend fun preview(preferRemote: Boolean): RemoteSyncPlan = withContext(Dispatchers.IO) {
        mutex.withLock { previewLocked(preferRemote) }
    }
    private suspend fun previewLocked(preferRemote: Boolean): RemoteSyncPlan {
        val local = store.read()
        local.profile.validate()
        require(local.profile.syncPassphrase.length >= 12)
        val snapshot = backend.capture().copy(exportedAt = 0)
        val remote = clientFactory(local.profile).get(FILE)
        currentCoroutineContext().ensureActive()
        val document = remote?.let { SyncCrypto.decrypt(it.bytes, local.profile.syncPassphrase) } ?: SyncDocument()
        val projection = SyncProjection.capture(snapshot, local.progress)
        var merged = SyncMergeEngine.merge(projection, local.baseline, document, local.deviceId, preferRemote)
        val materialized = SyncProjection.materialize(snapshot, SyncMergeEngine.live(merged.document))
        merged = merged.copy(document = SearchSyncProjection.trimHistory(merged.document, requireNotNull(materialized.backup.searchTools), local.deviceId))
        return RemoteSyncPlan(local, snapshot, remote, merged, materialized, document)
    }
    suspend fun apply(plan: RemoteSyncPlan) = withContext(Dispatchers.IO) { mutex.withLock { applyLocked(plan) } }
    private suspend fun applyLocked(plan: RemoteSyncPlan, guard: () -> Unit = {}) {
        currentCoroutineContext().ensureActive(); guard()
        if (store.read() != plan.local) throw SyncLocalChangedException()
        val bytes = SyncCrypto.encrypt(plan.merged.document, plan.local.profile.syncPassphrase)
        applyMetadata(plan.snapshot, plan.materialized.backup) {
            currentCoroutineContext().ensureActive(); guard()
            if (store.read() != plan.local) throw SyncLocalChangedException()
            clientFactory(plan.local.profile).put(FILE, bytes, plan.remote?.etag)
            currentCoroutineContext().ensureActive(); guard()
        }
        saveBaseline(plan, guard)
    }
    private fun saveBaseline(plan: RemoteSyncPlan, guard: () -> Unit) {
        guard()
        store.update { current ->
            if (current != plan.local) throw SyncLocalChangedException()
            current.copy(progress = plan.materialized.progress,
                baseline = SyncBaseline(plan.merged.document, SyncProjection.capture(plan.materialized.backup, plan.materialized.progress)))
        }
    }
    /** One mutex covers preview and application. Conflicts never publish in unattended mode. */
    suspend fun automatic(guard: () -> Unit = {}): AutomaticSyncOutcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            guard()
            val plan = previewLocked(true)
            guard()
            if (plan.merged.conflicts > 0) return@withLock AutomaticSyncOutcome.CONFLICT
            val projection = SyncProjection.capture(plan.snapshot, plan.local.progress)
            val desired = SyncProjection.capture(plan.materialized.backup, plan.materialized.progress)
            if (plan.remote != null && plan.remoteDocument == plan.merged.document && projection == desired) {
                if (backend.capture().copy(exportedAt = 0) != plan.snapshot) throw SyncLocalChangedException()
                saveBaseline(plan, guard)
                AutomaticSyncOutcome.UNCHANGED
            } else {
                applyLocked(plan, guard)
                AutomaticSyncOutcome.SYNCED
            }
        }
    }
}
