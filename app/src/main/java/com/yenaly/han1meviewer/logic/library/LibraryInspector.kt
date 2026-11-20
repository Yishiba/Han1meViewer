package com.yenaly.han1meviewer.logic.library

import com.yenaly.han1meviewer.logic.entity.download.HanimeDownloadEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class FileStatus { AVAILABLE, MISSING, UNAVAILABLE, EMPTY, CHANGED }
data class FileProbe(val status: FileStatus, val bytes: Long = -1, val identity: String = "", val modified: Long? = null)
data class InspectedFile(val video: HanimeDownloadEntity, val probe: FileProbe)
data class DuplicateFiles(val files: List<InspectedFile>, val sameReference: Boolean)
data class InspectionReport(
    val source: List<HanimeDownloadEntity>,
    val issues: List<InspectedFile>,
    val duplicates: List<DuplicateFiles>,
    val largeFiles: List<InspectedFile>,
    val thresholdMiB: Long = 500,
)
enum class InspectionStage { FILES, HASHES }
data class InspectionProgress(val stage: InspectionStage = InspectionStage.FILES, val completed: Int = 0, val total: Int = 0)
data class InspectionState(
    val running: Boolean = false, val progress: InspectionProgress = InspectionProgress(),
    val report: InspectionReport? = null, val stale: Boolean = false, val failed: Boolean = false,
)

interface LibraryFileAccess {
    fun referenceKey(uri: String): String = uri
    suspend fun probe(uri: String): FileProbe
    suspend fun sha256(uri: String, expectedBytes: Long): String
}

class LibraryInspector(private val access: LibraryFileAccess) {
    suspend fun scan(
        videos: List<HanimeDownloadEntity>, largeThresholdMiB: Long,
        onProgress: (InspectionProgress) -> Unit = {},
    ): InspectionReport {
        require(largeThresholdMiB in 1..MediaLibraryRule.MAX_SIZE_MIB)
        val source = videos.filter { it.isDownloaded }.map { it.copy() }
        val issues = mutableListOf<InspectedFile>()
        val available = mutableListOf<InspectedFile>()
        onProgress(InspectionProgress(InspectionStage.FILES, 0, source.size))
        for ((index, video) in source.withIndex()) {
            currentCoroutineContext().ensureActive()
            val probe = access.probe(video.videoUri)
            val entry = InspectedFile(video, if (probe.status == FileStatus.AVAILABLE && probe.bytes == 0L)
                probe.copy(status = FileStatus.EMPTY) else probe)
            if (entry.probe.status == FileStatus.AVAILABLE) available += entry else issues += entry
            onProgress(InspectionProgress(InspectionStage.FILES, index + 1, source.size))
        }
        val references = available.groupBy { it.probe.identity }
        val duplicates = references.values.filter { it.size > 1 }.map { DuplicateFiles(it, true) }.toMutableList()
        val physicalFiles = references.values.map { it.first() }
        val candidates = physicalFiles.filter { it.probe.bytes > 0 }.groupBy { it.probe.bytes }
            .values.filter { it.size > 1 }.flatten()
        val hashes = mutableMapOf<Pair<Long, String>, MutableList<InspectedFile>>()
        if (candidates.isNotEmpty()) onProgress(InspectionProgress(InspectionStage.HASHES, 0, candidates.size))
        for ((index, entry) in candidates.withIndex()) {
            currentCoroutineContext().ensureActive()
            try {
                val hash = access.sha256(entry.video.videoUri, entry.probe.bytes)
                val after = access.probe(entry.video.videoUri)
                if (after == entry.probe) {
                    hashes.getOrPut(entry.probe.bytes to hash) { mutableListOf() }.add(entry)
                } else issues += entry.copy(probe = after.copy(status = FileStatus.CHANGED))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                issues += entry.copy(probe = FileProbe(FileStatus.UNAVAILABLE))
            }
            onProgress(InspectionProgress(InspectionStage.HASHES, index + 1, candidates.size))
        }
        duplicates += hashes.values.filter { it.size > 1 }.map { DuplicateFiles(it, false) }
        val unstableIds = issues.map { it.video.id }.toSet()
        return InspectionReport(source, issues, duplicates,
            physicalFiles.filter { it.video.id !in unstableIds && it.probe.bytes >= largeThresholdMiB * MediaLibraryRule.MIB }
                .sortedByDescending { it.probe.bytes }, largeThresholdMiB)
    }
}
