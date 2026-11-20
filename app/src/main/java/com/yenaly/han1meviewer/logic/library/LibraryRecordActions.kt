package com.yenaly.han1meviewer.logic.library

import androidx.room.withTransaction
import com.yenaly.han1meviewer.logic.dao.DownloadDatabase
import com.yenaly.han1meviewer.logic.entity.download.HanimeDownloadEntity

/** Confirmation is based on a snapshot; mutation always rechecks the live row and file. */
class LibraryRecordActions(
    private val database: DownloadDatabase,
    private val access: LibraryFileAccess,
    private val deletePhysicalFile: (HanimeDownloadEntity) -> Boolean,
) {
    suspend fun deleteFile(expected: HanimeDownloadEntity): Boolean = database.withTransaction {
        val dao = database.hanimeDownloadDao
        val current = dao.findById(expected.id) ?: return@withTransaction false
        if (current.videoUri != expected.videoUri || !current.isDownloaded) return@withTransaction false
        val key = access.referenceKey(current.videoUri)
        if (dao.getAll().any { it.id != current.id && access.referenceKey(it.videoUri) == key }) return@withTransaction false
        if (!deletePhysicalFile(current)) return@withTransaction false
        dao.delete(current)
        true
    }

    suspend fun removeMissing(expected: HanimeDownloadEntity): Boolean = database.withTransaction {
        val dao = database.hanimeDownloadDao
        val current = dao.findById(expected.id) ?: return@withTransaction false
        if (current.videoUri != expected.videoUri || !current.isDownloaded ||
            access.probe(current.videoUri).status != FileStatus.MISSING) return@withTransaction false
        dao.delete(current)
        true
    }
}
