package com.yenaly.han1meviewer.worker

import android.util.Log
import androidx.lifecycle.Observer
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.DatabaseRepo
import com.yenaly.han1meviewer.logic.dao.DownloadDatabase
import com.yenaly.han1meviewer.logic.entity.download.HanimeDownloadEntity
import com.yenaly.han1meviewer.logic.state.DownloadState
import com.yenaly.han1meviewer.util.runSuspendCatching
import com.yenaly.yenaly_libs.utils.applicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 下载任务和等待队列只在 Channel 消费者中修改；WorkManager 负责执行和重试。
 */
object HanimeDownloadManagerV2 {

    private const val TAG = "HanimeDownloadManager"

    const val MAX_CONCURRENT_DOWNLOAD_DEF = 2
    @Volatile var maxConcurrentDownloadCount = Preferences.downloadCountLimit.let {
        if (it > 0) it else Int.MAX_VALUE
    }
        set(value) {
            field = if (value > 0) value else Int.MAX_VALUE
            send(DownloadMsg.ProcessNext)
        }

    private val workManager = WorkManager.getInstance(applicationContext)

    // Channel 内部状态：保存正在下载任务与等待队列
    private data class ActiveDownload(
        val args: HanimeDownloadWorker.Args,
        val runId: Long,
        val job: Job
    )
    private data class QueuedDownload(val args: HanimeDownloadWorker.Args, val redownload: Boolean = false)
    private val activeDownloads = linkedMapOf<String, ActiveDownload>()
    private val waitingQueue = ArrayDeque<QueuedDownload>()
    private var nextRunId = 0L

    // 协程 Scope，用于管理 channel 与任务协程
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Channel 消息类型
    private sealed class DownloadMsg {
        /**
         * 添加下载任务
         */
        data class Add(
            val args: HanimeDownloadWorker.Args,
            val redownload: Boolean = false,
            val waiting: Boolean = false,
            val state: DownloadState = DownloadState.Unknown
        ) : DownloadMsg()

        /**
         * 恢复下载任务（暂停 => 下载）
         */
        data class Resume(val args: HanimeDownloadWorker.Args) : DownloadMsg()

        /**
         * 停止下载任务
         */
        data class Stop(val args: HanimeDownloadWorker.Args) : DownloadMsg()

        /**
         * 删除下载任务
         */
        data class Delete(val args: HanimeDownloadWorker.Args) : DownloadMsg()

        /**
         * 处理下一个任务
         */
        data object ProcessNext : DownloadMsg()
        data class Finished(val videoCode: String, val runId: Long) : DownloadMsg()
    }

    private val pendingCommands = java.util.concurrent.atomic.AtomicInteger()
    private val runningTasks = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var hasWaitingTasks = false
    val hasPendingTasks: Boolean get() = pendingCommands.get() > 0 || runningTasks.get() > 0 || hasWaitingTasks
    private val backups get() = com.yenaly.han1meviewer.logic.backup.BackupService.get(applicationContext)
    private fun send(message: DownloadMsg) {
        pendingCommands.incrementAndGet()
        if (downloadChannel.trySend(message).isFailure) {
            pendingCommands.decrementAndGet()
            error("Download command channel is closed")
        }
    }
    private val downloadChannel = Channel<DownloadMsg>(capacity = Channel.UNLIMITED)

    init {
        scope.launch {
            for (msg in downloadChannel) {
                try {
                    if (backups.recoveryRequired.value) continue
                    backups.withDownloadAccess {
                        when (msg) {
                            is DownloadMsg.Add -> {
                                if (msg.args.videoCode in activeDownloads) {
                                    Log.d(TAG, "任务已存在：${msg.args.videoCode}")
                                } else if (waitingQueue.any { it.args.videoCode == msg.args.videoCode }) {
                                    Log.d(TAG, "任务已在等待队列：${msg.args.videoCode}")
                                } else {
                                    // Unknown 代表任务刚添加，未开始状态流转
                                    if (!msg.waiting && activeDownloads.size < maxConcurrentDownloadCount &&
                                        (msg.state == DownloadState.Downloading || msg.state == DownloadState.Unknown)
                                    ) {
                                        Log.d(TAG, "添加任务：${msg.args.videoCode}")
                                        activate(msg.args, msg.redownload)
                                    } else {
                                        Log.d(TAG, "任务已满，加入等待队列：${msg.args.videoCode}")
                                        when (msg.state) {
                                            DownloadState.Downloading -> {
                                                // 之前为 Downloading 的优先级更高
                                                waitingQueue.addFirst(QueuedDownload(msg.args, msg.redownload))
                                                workManager.cancelUniqueWork(msg.args.videoCode).await()
                                                enqueueWaitingWork(msg.args)
                                            }

                                            DownloadState.Queued, DownloadState.Unknown -> {
                                                waitingQueue.addLast(QueuedDownload(msg.args, msg.redownload))
                                                if (msg.state == DownloadState.Queued) {
                                                    workManager.cancelUniqueWork(msg.args.videoCode).await()
                                                }
                                                enqueueWaitingWork(msg.args)
                                            }

                                            else -> Unit
                                        }
                                    }
                                }
                            }

                            is DownloadMsg.Resume -> {
                                if (msg.args.videoCode in activeDownloads) {
                                    Log.d(TAG, "任务已在下载中，无需恢复：${msg.args.videoCode}")
                                } else {
                                    waitingQueue.removeIf { it.args.videoCode == msg.args.videoCode }
                                    Log.d(TAG, "恢复任务：${msg.args.videoCode}")
                                    // 如果 active 已满，则暂停一个任务，加入等待队列
                                    while (activeDownloads.size >= maxConcurrentDownloadCount && activeDownloads.isNotEmpty()) {
                                        val (videoCode, task) = activeDownloads.entries.first()
                                        activeDownloads.remove(videoCode)
                                        task.job.cancel()
                                        stopWork(task.args)
                                        waitingQueue.addLast(QueuedDownload(task.args))
                                        markQueued(task.args)
                                        Log.d(TAG, "任务已满，暂停任务：$videoCode")
                                    }
                                    activate(msg.args, redownload = false)
                                }
                            }

                            is DownloadMsg.Stop -> {
                                val active = activeDownloads.remove(msg.args.videoCode)
                                if (active != null) {
                                    Log.d(TAG, "停止任务：${msg.args.videoCode}")
                                    active.job.cancel()
                                    stopWork(msg.args)
                                    processNext()
                                } else {
                                    Log.d(TAG, "停止等待任务：${msg.args.videoCode}")
                                    waitingQueue.removeIf { it.args.videoCode == msg.args.videoCode }
                                    workManager.cancelUniqueWork(msg.args.videoCode).await()
                                    markPaused(msg.args)
                                }
                            }

                            is DownloadMsg.Delete -> {
                                val active = activeDownloads.remove(msg.args.videoCode)
                                if (active != null) {
                                    Log.d(TAG, "从正在下载列表中删除任务：${msg.args.videoCode}")
                                    active.job.cancel()
                                } else {
                                    waitingQueue.removeIf { it.args.videoCode == msg.args.videoCode }
                                    Log.d(TAG, "从等待队列中删除任务：${msg.args.videoCode}")
                                }
                                deleteWork(msg.args)
                                processNext()
                            }

                            DownloadMsg.ProcessNext -> processNext()
                            is DownloadMsg.Finished -> {
                                if (activeDownloads[msg.videoCode]?.runId == msg.runId) {
                                    activeDownloads.remove(msg.videoCode)
                                    Log.d(TAG, "launchDownload (end): ${msg.videoCode}")
                                    processNext()
                                }
                            }
                        }
                    }
                } finally {
                    hasWaitingTasks = waitingQueue.isNotEmpty()
                    pendingCommands.decrementAndGet()
                }
            }
        }
    }

    /**
     * 初始化，加载所有正在下载的任务
     */
    suspend fun init() {
        Log.d(TAG, "init")
        if (com.yenaly.han1meviewer.logic.backup.BackupService.get(
                com.yenaly.yenaly_libs.utils.applicationContext).recoveryRequired.value) return
        val allDownloading =
            DownloadDatabase.instance.hanimeDownloadDao.loadAllDownloadingHanimeOnce()
        allDownloading.forEach { entity ->
            val args = HanimeDownloadWorker.Args.fromEntity(entity)
            // addTask
            send(DownloadMsg.Add(args, state = entity.state))
        }
    }

    /**
     * 添加下载任务
     */
    fun addTask(
        args: HanimeDownloadWorker.Args,
        redownload: Boolean = false, waiting: Boolean = false
    ) {
        send(DownloadMsg.Add(args, redownload, waiting))
    }

    /**
     * 恢复下载任务
     */
    fun resumeTask(entity: HanimeDownloadEntity) {
        val args = HanimeDownloadWorker.Args.fromEntity(entity)
        send(DownloadMsg.Resume(args))
    }

    /**
     * 停止下载任务
     */
    fun stopTask(entity: HanimeDownloadEntity) {
        val args = HanimeDownloadWorker.Args.fromEntity(entity)
        send(DownloadMsg.Stop(args))
    }

    /**
     * 删除下载任务
     */
    fun deleteTask(entity: HanimeDownloadEntity) {
        val args = HanimeDownloadWorker.Args.fromEntity(entity)
        send(DownloadMsg.Delete(args))
    }

    /**
     * 处理等待队列中的下一个任务
     */
    private fun processNext() {
        Log.d(TAG, "processNext")
        while (activeDownloads.size < maxConcurrentDownloadCount && waitingQueue.isNotEmpty()) {
            val next = waitingQueue.removeFirst()
            activate(next.args, next.redownload)
        }
    }

    /**
     * 任务结束消息携带代次，旧任务的取消回调不会移除同一视频的新任务。
     */
    private fun activate(args: HanimeDownloadWorker.Args, redownload: Boolean) {
        val runId = ++nextRunId
        val job = launchDownload(args, redownload, runId)
        activeDownloads[args.videoCode] = ActiveDownload(args, runId, job)
    }

    private fun launchDownload(
        args: HanimeDownloadWorker.Args,
        redownload: Boolean,
        runId: Long
    ): Job {
        runningTasks.incrementAndGet()
        return scope.launch {
            try {
                backups.withDownloadAccess {
                    Log.d(TAG, "launchDownload (start): ${args.videoCode}")
                    val workId = startWork(args, redownload)
                    awaitWorkCompletion(args.videoCode, workId.toString())
                }
            } finally {
                send(DownloadMsg.Finished(args.videoCode, runId))
                runningTasks.decrementAndGet()
            }
        }
    }

    /**
     * 开启下载任务
     */
    private suspend fun startWork(
        args: HanimeDownloadWorker.Args,
        redownload: Boolean = false,
        waiting: Boolean = false,
        delete: Boolean = false
    ) = HanimeDownloadWorker.build(constraintsRequired = !delete) {
            setInputData(
                workDataOf(
                    HanimeDownloadWorker.QUALITY to args.quality,
                    HanimeDownloadWorker.DOWNLOAD_URL to args.downloadUrl,
                    HanimeDownloadWorker.VIDEO_TYPE to args.videoType,
                    HanimeDownloadWorker.HANIME_NAME to args.hanimeName,
                    HanimeDownloadWorker.VIDEO_CODE to args.videoCode,
                    HanimeDownloadWorker.COVER_URL to args.coverUrl,
                    HanimeDownloadWorker.REDOWNLOAD to redownload,
                    HanimeDownloadWorker.IN_WAITING_QUEUE to waiting,
                    HanimeDownloadWorker.DELETE to delete
                )
            )
        }.apply {
            workManager.beginUniqueWork(
                args.videoCode, ExistingWorkPolicy.REPLACE, this
            ).enqueue().await()
        }.id

    /**
     * 取消正在执行的 WorkManager 任务
     */
    private suspend fun stopWork(args: HanimeDownloadWorker.Args) {
        runSuspendCatching {
            workManager.cancelUniqueWork(args.videoCode).await()
            markPaused(args)
            Log.d(TAG, "stopWork (cancelUniqueWork): ${args.videoCode}")
        }.onFailure { t -> // 上述方法可能无法取消任务
            t.printStackTrace()
            markPaused(args)
        }
    }

    private suspend fun markQueued(args: HanimeDownloadWorker.Args) {
        DatabaseRepo.HanimeDownload.find(args.videoCode, args.quality.orEmpty())?.let { entity ->
            DatabaseRepo.HanimeDownload.update(entity.copy(state = DownloadState.Queued))
        }
    }

    private suspend fun enqueueWaitingWork(
        args: HanimeDownloadWorker.Args
    ) {
        val entity = DatabaseRepo.HanimeDownload.find(args.videoCode, args.quality.orEmpty())
        if (entity == null) {
            startWork(args, waiting = true)
        } else {
            DatabaseRepo.HanimeDownload.update(entity.copy(state = DownloadState.Queued))
        }
    }

    private suspend fun markPaused(args: HanimeDownloadWorker.Args) {
        DatabaseRepo.HanimeDownload.find(args.videoCode, args.quality.orEmpty())?.let { entity ->
            if (entity.state != DownloadState.Finished) {
                DatabaseRepo.HanimeDownload.update(entity.copy(state = DownloadState.Paused))
            }
        }
    }

    /**
     * 删除下载任务
     *
     * 删除操作交给 WorkManager 处理
     */
    private suspend fun deleteWork(args: HanimeDownloadWorker.Args) = startWork(args, delete = true)

    /**
     * 通过观察 WorkManager 的 LiveData 来阻塞等待任务完成
     */
    private suspend fun awaitWorkCompletion(videoCode: String, workId: String) =
        suspendCancellableCoroutine { cont ->
            val liveData = workManager.getWorkInfosForUniqueWorkLiveData(videoCode)
            Log.d(TAG, "获取 LiveData：$videoCode")
            val observer = object : Observer<List<WorkInfo>> {
                override fun onChanged(value: List<WorkInfo>) {
                    val info = value.firstOrNull { it.id.toString() == workId } ?: return
                    if (info.state.isFinished) {
                        Log.d(TAG, "任务完成，移除 observer：$videoCode")
                        liveData.removeObserver(this)
                        cont.resume(Unit)
                    }
                }
            }
            CoroutineScope(Dispatchers.Main).launch {
                liveData.observeForever(observer)
                Log.d(TAG, "添加 observer：$videoCode")
            }
            cont.invokeOnCancellation { liveData.removeObserver(observer) }
        }
}
