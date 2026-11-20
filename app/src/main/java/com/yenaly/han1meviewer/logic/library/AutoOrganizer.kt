package com.yenaly.han1meviewer.logic.library

import android.content.SharedPreferences
import android.util.Log
import androidx.room.withTransaction
import com.yenaly.han1meviewer.logic.dao.DownloadDatabase
import com.yenaly.han1meviewer.logic.entity.download.DownloadGroupEntity
import com.yenaly.han1meviewer.logic.entity.download.HanimeDownloadEntity
import com.yenaly.han1meviewer.logic.state.DownloadState

data class GroupAssignment(val recordId: Int, val targetGroupId: Int, val ruleName: String, val title: String = "", val quality: String = "")

object AutoOrganizer {
    fun target(video: HanimeDownloadEntity, rules: List<SmartCollection>, groups: Set<Int>, now: Long): GroupAssignment? {
        if (!video.isDownloaded || video.groupId != DownloadGroupEntity.DEFAULT_GROUP_ID) return null
        val rule = rules.firstOrNull {
            it.autoEnabled && it.autoTargetGroupId in groups &&
                it.autoTargetGroupId != DownloadGroupEntity.DEFAULT_GROUP_ID && it.rule.matches(video, now)
        } ?: return null
        return GroupAssignment(video.id, requireNotNull(rule.autoTargetGroupId), rule.name, video.title, video.quality)
    }

    fun preview(videos: List<HanimeDownloadEntity>, rules: List<SmartCollection>, groups: Set<Int>, now: Long) =
        videos.mapNotNull { target(it, rules, groups, now) }
}

/** Called by the worker even when no download screen is open. No file is moved on disk. */
class DownloadAutoOrganizer(private val database: DownloadDatabase, private val preferences: SharedPreferences) {
    private fun rules() = MediaLibraryCodec.decodeCollections(preferences.getString(SmartCollectionStore.KEY, null))

    suspend fun complete(recordId: Int, downloadedBytes: Long) = database.withTransaction {
        val dao = database.hanimeDownloadDao
        val current = dao.findById(recordId) ?: return@withTransaction
        val finished = current.copy(state = DownloadState.Finished, downloadedLength = downloadedBytes)
        dao.update(finished)
        if (current.isDownloaded) return@withTransaction // Idempotent worker retry; preserve manual decisions.
        val saved = runCatching { rules() }.getOrElse {
            Log.w("LibraryOrganizer", "Rules unreadable; completed download left in its current group")
            return@withTransaction
        }
        val groups = database.downloadGroupDao.getAllGroupsOnce().map { it.id }.toSet()
        AutoOrganizer.target(finished, saved, groups, System.currentTimeMillis())?.let { assignment ->
            dao.moveFinished(recordId, DownloadGroupEntity.DEFAULT_GROUP_ID, assignment.targetGroupId)
            Log.d("LibraryOrganizer", "Completed record=$recordId target=${assignment.targetGroupId}")
        }
    }

    suspend fun preview(): List<GroupAssignment> = database.withTransaction {
        AutoOrganizer.preview(database.hanimeDownloadDao.getAll(), rules(),
            database.downloadGroupDao.getAllGroupsOnce().map { it.id }.toSet(), System.currentTimeMillis())
    }

    /** Recheck every proposed destination inside one transaction; stale previews make no changes. */
    suspend fun apply(expected: List<GroupAssignment>): Int = database.withTransaction {
        val current = preview().associateBy { it.recordId }
        require(expected.all { current[it.recordId] == it }) { "Organization preview changed" }
        expected.forEach {
            check(database.hanimeDownloadDao.moveFinished(it.recordId, DownloadGroupEntity.DEFAULT_GROUP_ID, it.targetGroupId) == 1)
        }
        expected.size
    }
}
