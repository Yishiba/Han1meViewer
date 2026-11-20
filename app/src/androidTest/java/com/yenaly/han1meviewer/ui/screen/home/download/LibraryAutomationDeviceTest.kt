package com.yenaly.han1meviewer.ui.screen.home.download

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.yenaly.han1meviewer.logic.dao.DownloadDatabase
import com.yenaly.han1meviewer.logic.entity.download.DownloadGroupEntity
import com.yenaly.han1meviewer.logic.library.*
import com.yenaly.han1meviewer.logic.state.DownloadState
import com.yenaly.han1meviewer.util.SafFileManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class LibraryAutomationDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun database() = Room.inMemoryDatabaseBuilder(context, DownloadDatabase::class.java).build()
    private inline fun <T> withDatabase(block: (DownloadDatabase) -> T): T {
        val db = database()
        try { return block(db) } finally { db.close() }
    }
    private suspend fun groups(db: DownloadDatabase) {
        db.downloadGroupDao.insertDefaultGroup()
        db.downloadGroupDao.insert(DownloadGroupEntity("HD", 1, 2))
        db.downloadGroupDao.insert(DownloadGroupEntity("Other", 2, 3))
    }
    private fun rule(id: String, target: Int) = SmartCollection(id, id, MediaLibraryRule(keyword = "Alpha"), true, target)

    @Test fun documentAndTreeUrisForOneDocumentHaveOneReferenceIdentity() {
        val access = AndroidLibraryFileAccess(context)
        val direct = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Movies/a.mp4")
        val tree = DocumentsContract.buildDocumentUriUsingTree(
            Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMovies"), "primary:Movies/a.mp4")
        assertEquals(access.referenceKey(direct.toString()), access.referenceKey(tree.toString()))
    }

    @Test fun workerCompletionPriorityManualGroupsRetryAndLateCoverWrite() = runBlocking {
        val prefs = context.getSharedPreferences("auto-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        withDatabase { db ->
            groups(db)
            SmartCollectionStore(prefs).use { store ->
                try {
                    assertTrue(store.save(rule("first", 2)))
                    assertTrue(store.save(rule("second", 3)))
                    val dao = db.hanimeDownloadDao
                    dao.insert(libraryItem(1).video.copy(state = DownloadState.Downloading))
                    val organizer = DownloadAutoOrganizer(db, prefs)
                    organizer.complete(1, 100)
                    assertEquals(2, dao.findById(1)?.groupId)
                    assertEquals(DownloadState.Finished, dao.findById(1)?.state)
                    dao.updateCoverUri(1, "file:///cover.png")
                    assertEquals(2, dao.findById(1)?.groupId)
                    assertEquals(DownloadState.Finished, dao.findById(1)?.state)
                    dao.moveFinished(1, 2, 3)
                    organizer.complete(1, 100)
                    assertEquals(3, dao.findById(1)?.groupId)
                    dao.moveFinished(1, 3, 1)
                    organizer.complete(1, 100)
                    assertEquals(1, dao.findById(1)?.groupId)
                    dao.insert(libraryItem(2).video.copy(groupId = 3, state = DownloadState.Downloading))
                    organizer.complete(2, 100)
                    assertEquals(3, dao.findById(2)?.groupId)
                    assertTrue(store.move("second", -1))
                    assertEquals(listOf("second", "first"), store.catalog.value.collections.map { it.id })
                    dao.insert(libraryItem(3).video.copy(state = DownloadState.Downloading))
                    organizer.complete(3, 100)
                    assertEquals(3, dao.findById(3)?.groupId)
                    prefs.edit().putString(SmartCollectionStore.KEY, "broken").commit()
                    dao.insert(libraryItem(4).video.copy(state = DownloadState.Downloading))
                    organizer.complete(4, 100)
                    assertEquals(1, dao.findById(4)?.groupId)
                    assertTrue(dao.findById(4)?.isDownloaded == true)
                } finally { prefs.edit().clear().commit() }
            }
        }
    }

    @Test fun existingDownloadsRequirePreviewAndStalePlansApplyNothing() = runBlocking {
        val prefs = context.getSharedPreferences("auto-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        withDatabase { db ->
            groups(db)
            SmartCollectionStore(prefs).use { store ->
                try {
                    store.save(rule("rule", 2))
                    val dao = db.hanimeDownloadDao
                    dao.insert(libraryItem(1).video)
                    dao.insert(libraryItem(2).video)
                    val organizer = DownloadAutoOrganizer(db, prefs)
                    val plan = organizer.preview()
                    assertEquals(2, plan.size)
                    assertEquals(1, dao.findById(1)?.groupId)
                    dao.moveFinished(2, 1, 3)
                    try { organizer.apply(plan); fail("Stale plan must fail") } catch (_: IllegalArgumentException) { }
                    assertEquals(1, dao.findById(1)?.groupId)
                    assertEquals(3, dao.findById(2)?.groupId)
                    assertEquals(1, organizer.apply(organizer.preview()))
                    assertEquals(2, dao.findById(1)?.groupId)
                    assertEquals(3, dao.findById(2)?.groupId)
                } finally { prefs.edit().clear().commit() }
            }
        }
    }

    @Test fun actualFileHashingMissingAndRevokedProviderAreDistinguished() = runBlocking {
        val dir = File(context.cacheDir, "inspect-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val a = File(dir, "a.mp4").apply { writeBytes(ByteArray(1024 * 1024) { 1 }) }
            val b = File(dir, "b.mp4").apply { writeBytes(a.readBytes()) }
            val c = File(dir, "c.mp4").apply { writeBytes(ByteArray(1024 * 1024) { 2 }) }
            val source = listOf(libraryItem(1, uri = a.toURI().toString()).video,
                libraryItem(2, uri = b.toURI().toString()).video, libraryItem(3, uri = c.toURI().toString()).video,
                libraryItem(4, uri = File(dir, "missing.mp4").toURI().toString()).video,
                libraryItem(5, uri = "content://no.such.provider/video").video)
            val access = AndroidLibraryFileAccess(context)
            val result = LibraryInspector(access).scan(source, 1)
            assertEquals(listOf(1, 2), result.duplicates.single().files.map { it.video.id })
            assertEquals(3, result.largeFiles.size)
            assertEquals(FileStatus.MISSING, result.issues.find { it.video.id == 4 }?.probe?.status)
            assertEquals(FileStatus.UNAVAILABLE, result.issues.find { it.video.id == 5 }?.probe?.status)
            assertEquals(access.sha256(source[0].videoUri, a.length()), access.sha256(source[1].videoUri, b.length()))
        } finally { dir.deleteRecursively() }
    }

    @Test fun cleanupRechecksRestoredFilesChangedUrisAndSharedReferences() = runBlocking {
        val dir = File(context.cacheDir, "inspect-${UUID.randomUUID()}").apply { mkdirs() }
        withDatabase { db ->
            groups(db)
            try {
                val dao = db.hanimeDownloadDao
                val access = AndroidLibraryFileAccess(context)
                val actions = LibraryRecordActions(db, access) { SafFileManager.deleteDownloadedFile(context, it) }
                val file = File(dir, "a.mp4")
                val expected = libraryItem(1, uri = file.toURI().toString()).video
                dao.insert(expected)
                file.writeText("restored")
                assertFalse(actions.removeMissing(expected))
                assertTrue(file.exists())
                val alias = expected.copy(id = 2, videoUri = File(dir, "./a.mp4").toURI().toString())
                dao.insert(alias)
                assertFalse(actions.deleteFile(expected))
                assertTrue(file.exists())
                dao.delete(alias)
                assertTrue(actions.deleteFile(expected))
                assertNull(dao.findById(1))
                assertFalse(file.exists())
                dao.insert(expected)
                val replacement = File(dir, "replacement.mp4").apply { writeText("keep") }
                dao.update(expected.copy(videoUri = replacement.toURI().toString()))
                assertFalse(actions.removeMissing(expected))
                assertFalse(actions.deleteFile(expected))
                assertTrue(replacement.exists())
                dao.insert(libraryItem(3, uri = "content://no.such.provider/video").video)
                assertFalse(actions.removeMissing(requireNotNull(dao.findById(3))))
                dao.insert(expected.copy(id = 4))
                assertTrue(actions.removeMissing(expected.copy(id = 4)))
                assertNull(dao.findById(4))
            } finally { dir.deleteRecursively() }
        }
    }
}
