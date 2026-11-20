package com.yenaly.han1meviewer.ui.screen.settings.backup

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.await
import com.yenaly.han1meviewer.worker.HanimeDownloadWorker
import com.yenaly.han1meviewer.logic.backup.*
import com.yenaly.han1meviewer.logic.dao.*
import com.yenaly.han1meviewer.logic.entity.*
import com.yenaly.han1meviewer.logic.entity.download.*
import com.yenaly.han1meviewer.logic.library.*
import com.yenaly.han1meviewer.logic.state.DownloadState
import com.yenaly.han1meviewer.util.await
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

class BackupDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun grantFixture(revoke: Boolean = false) {
        val done = CountDownLatch(1)
        var result = 0
        val intent = Intent().setComponent(ComponentName(InstrumentationRegistry.getInstrumentation().context.packageName,
            BackupTestGrantReceiver::class.java.name)).putExtra("target", context.packageName).putExtra("revoke", revoke)
        context.sendOrderedBroadcast(intent, null, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { result = resultCode; done.countDown() }
        }, Handler(Looper.getMainLooper()), 0, null, null)
        assertTrue("Fixture grant receiver must finish", done.await(10, TimeUnit.SECONDS))
        assertEquals(android.app.Activity.RESULT_OK, result)
    }
    private fun snapshot(): BackupPayload {
        val rule = SmartCollection("hd", "HD", MediaLibraryRule(qualities = setOf("1080p")), true, 2)
        return BackupPayload(version = 3, searchTools = com.yenaly.han1meviewer.logic.translation.SearchToolsData(), settings = mapOf(
            "sample" to PreferenceValue.IntValue(42),
            SmartCollectionStore.KEY to PreferenceValue.StringValue(MediaLibraryCodec.encodeCollections(listOf(rule)))),
            downloadGroups = listOf(DownloadGroupEntity("Default", 0, 1), DownloadGroupEntity("HD", 1, 2)),
            downloads = listOf(HanimeDownloadEntity(groupId = 2, coverUrl = "cover", coverUri = null,
                title = "Sample", addDate = 123, videoCode = "V1", videoUri = "file:///unchanged.mp4",
                quality = "1080p", videoUrl = "url", length = 10, downloadedLength = 10, state = DownloadState.Finished, id = 1)),
            downloadCategories = listOf(DownloadCategoryEntity("Tag", 1)),
            downloadCategoryCrossRefs = listOf(HanimeCategoryCrossRef(1, 1)),
            watchHistories = listOf(WatchHistoryEntity("cover", "Sample", 1, 2, "V1", 1234, 1)),
            hKeyframes = listOf(HKeyframeEntity("V1", "Sample", mutableListOf(HKeyframeEntity.Keyframe(1000, "marker")))),
            checkInRecords = listOf(CheckInRecordEntity(1, "2026-11-17", "12:00", "type", "Sample\u001EV1", "feeling")),
            sideDishes = listOf(SideDishEntity("V1", "Sample", "cover")))
    }

    private suspend fun isolated(block: suspend (AndroidBackupBackend, android.content.SharedPreferences, DownloadDatabase) -> Unit) {
        val prefsName = "backup-test-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val downloads = Room.inMemoryDatabaseBuilder(context, DownloadDatabase::class.java).build()
        val history = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        val misc = Room.inMemoryDatabaseBuilder(context, MiscellanyDatabase::class.java).build()
        val checkIn = Room.inMemoryDatabaseBuilder(context, CheckInRecordDatabase::class.java).build()
        try { block(AndroidBackupBackend(context, prefs, downloads, history, misc, checkIn,
            com.yenaly.han1meviewer.logic.translation.SearchToolsStore(context, context.getSharedPreferences(prefsName + "-search", Context.MODE_PRIVATE))), prefs, downloads) }
        finally { listOf(downloads, history, misc, checkIn).forEach { it.close() }; context.deleteSharedPreferences(prefsName); context.deleteSharedPreferences(prefsName + "-search") }
    }

    @Test fun allDatabaseSectionsRulesAndPreferenceTypesRoundTripWithLoginPreserved() = runBlocking {
        isolated { backend, prefs, downloads ->
            prefs.edit().putString("cookie", "current-login").putBoolean("already_login", true)
                .putBoolean("usage_notice_accepted", true).putString("obsolete", "remove").commit()
            val source = snapshot()
            backend.apply(BackupCodec.decode(BackupCodec.encode(source)))
            val restored = backend.capture()
            assertEquals(source.settings, restored.settings)
            assertEquals(source.downloads, restored.downloads)
            assertEquals(source.downloadGroups, restored.downloadGroups)
            assertEquals(source.downloadCategories, restored.downloadCategories)
            assertEquals(source.downloadCategoryCrossRefs, restored.downloadCategoryCrossRefs)
            assertEquals(source.watchHistories, restored.watchHistories)
            assertEquals(source.hKeyframes, restored.hKeyframes)
            assertEquals(source.checkInRecords, restored.checkInRecords)
            assertEquals(source.sideDishes, restored.sideDishes)
            assertFalse(prefs.contains("obsolete"))
            assertEquals("current-login", prefs.getString("cookie", null))
            assertTrue(prefs.getBoolean("already_login", false))
            assertTrue(prefs.getBoolean("usage_notice_accepted", false))
            SmartCollectionStore(prefs).use { assertTrue(it.catalog.value.collections.single().autoEnabled) }
            assertEquals("file:///unchanged.mp4", downloads.hanimeDownloadDao.findById(1)?.videoUri)
        }
    }

    @Test fun restoreFailureAfterRealRoomWritesRollsBackAllSectionsAndSupportsSafetyRestore() = runBlocking {
        isolated { backend, prefs, _ ->
            val folder = File(context.cacheDir, "backup-test-${UUID.randomUUID()}")
            try {
                val source = snapshot()
                backend.apply(source)
                var failNext = true
                val fault = object : BackupBackend {
                    override suspend fun capture() = backend.capture()
                    override suspend fun apply(payload: BackupPayload) {
                        backend.apply(payload)
                        if (failNext) { failNext = false; throw IOException("Injected failure after Room commits") }
                    }
                }
                val vault = BackupVault(folder)
                val repository = BackupRepository(vault, fault)
                val changed = source.copy(settings = mapOf("sample" to PreferenceValue.IntValue(7)),
                    downloads = emptyList(), downloadCategoryCrossRefs = emptyList(), watchHistories = emptyList())
                try { repository.import(BackupCodec.encode(changed), 3); fail() } catch (_: IOException) { }
                assertEquals(source.downloads, backend.capture().downloads)
                assertEquals(source.watchHistories, backend.capture().watchHistories)
                assertEquals(42, prefs.getInt("sample", 0))
                assertFalse(vault.hasJournal())
                repository.import(BackupCodec.encode(changed), 3)
                assertTrue(backend.capture().downloads!!.isEmpty())
                repository.restoreVersion(vault.list().first { it.kind == BackupKind.BEFORE_RESTORE }.id, 3)
                assertEquals(source.downloads, backend.capture().downloads)
            } finally { folder.deleteRecursively() }
        }
    }

    @Test fun unfinishedDownloadsStayPausedAndInvalidImportsHaveNoSideEffects() = runBlocking {
        isolated { backend, _, downloads ->
            val folder = File(context.cacheDir, "backup-test-${UUID.randomUUID()}")
            try {
                val source = snapshot().let { it.copy(downloads = it.downloads!!.map { row -> row.copy(state = DownloadState.Queued) }) }
                backend.apply(source)
                assertEquals(DownloadState.Paused, downloads.hanimeDownloadDao.findById(1)?.state)
                val before = backend.capture()
                val repository = BackupRepository(BackupVault(folder), backend)
                try { repository.import("{invalid}".toByteArray(), 3); fail() } catch (_: Exception) { }
                assertEquals(before.downloads, backend.capture().downloads)
                assertTrue(repository.list().isEmpty())
            } finally { folder.deleteRecursively() }
        }
    }

    @Test fun automaticConfigurationPersistsAndUniquePeriodicScheduleCanBeUpdatedAndDisabled() = runBlocking {
        val service = BackupService.get(context)
        service.initialize()
        val original = service.state.value.settings
        try {
            service.configure(original.copy(enabled = false))
            val count = service.state.value.versions.size
            assertNull(service.create(automatic = true))
            assertEquals(count, service.state.value.versions.size)
            service.configure(original.copy(enabled = true, days = 7, keep = 3, mirrorUri = null))
            service.configure(service.state.value.settings.copy(days = 1))
            val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(BackupService.WORK_NAME).await()
            assertEquals(1, work.count { !it.state.isFinished })
            assertEquals(1, BackupSettingsStore(context).read().days)
            assertEquals(3, BackupSettingsStore(context).read().keep)
            service.configure(service.state.value.settings.copy(enabled = false))
            assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(BackupService.WORK_NAME).await().all { it.state.isFinished })
        } finally { service.configure(original) }
    }

    @Test fun pendingDownloadsBlockRestoreAndUnwritableMirrorPreservesVerifiedLocalVersion() = runBlocking {
        val service = BackupService.get(context)
        service.initialize()
        val original = service.state.value.settings
        val manager = WorkManager.getInstance(context)
        val task = OneTimeWorkRequestBuilder<HanimeDownloadWorker>().setInitialDelay(1, TimeUnit.DAYS)
            .addTag(HanimeDownloadWorker.TAG).build()
        var version: BackupVersion? = null
        try {
            service.configure(original.copy(enabled = false, mirrorUri = "content://no.such.backup.provider/tree/test"))
            version = requireNotNull(service.create())
            assertTrue(service.state.value.settings.mirrorFailed)
            assertFalse(service.state.value.settings.lastFailure)
            val vault = BackupVault(File(context.noBackupFilesDir, "backup-versions"))
            BackupCodec.decode(vault.read(version.id))
            manager.enqueue(task).await()
            try { service.restore(version.id); fail("Pending work must block restore") } catch (_: BackupBusyException) { }
            assertFalse(vault.hasJournal())
        } finally {
            manager.cancelWorkById(task.id).await()
            version?.let { service.delete(it.id) }
            service.configure(original)
        }
    }

    @Test fun documentFolderCopyAndSelectedVersionExportContainTheVerifiedPayloadAndSurviveLocalDeletion() = runBlocking {
        val service = BackupService.get(context)
        service.initialize()
        val original = service.state.value.settings
        val tree = DocumentsContract.buildTreeDocumentUri("com.yenaly.han1meviewer.backup.test.documents", "root")
        val folder = requireNotNull(DocumentFile.fromTreeUri(context, tree))
        var version: BackupVersion? = null
        grantFixture()
        try {
            context.contentResolver.takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertTrue("Granted fixture directory must be readable", folder.isDirectory)
            assertTrue("Granted fixture directory must be writable", folder.canWrite())
            folder.listFiles().forEach { it.delete() }
            service.configure(original.copy(enabled = false, mirrorUri = tree.toString()))
            version = requireNotNull(service.create())
            assertFalse(service.state.value.settings.mirrorFailed)
            val copy = folder.listFiles().single()
            assertTrue(copy.name!!.endsWith(".json"))
            val bytes = context.contentResolver.openInputStream(copy.uri)!!.use { BackupCodec.readBounded(it) }
            assertEquals(version.sha256, BackupCodec.digest(bytes))
            BackupCodec.decode(bytes)
            val exported = requireNotNull(folder.createFile("application/json", "exported.json"))
            service.export(version.id, exported.uri)
            assertArrayEquals(bytes, context.contentResolver.openInputStream(exported.uri)!!.use { BackupCodec.readBounded(it) })
            service.delete(version.id)
            version = null
            assertTrue(copy.exists())
        } finally {
            version?.let { service.delete(it.id) }
            folder.listFiles().forEach { it.delete() }
            service.configure(original)
            grantFixture(revoke = true)
        }
    }
    @Test fun encryptedFolderCopiesAndExportsRejectWrongPasswordWithoutMutationAndNeverFallBackToPlain() = runBlocking {
        val service = BackupService.get(context)
        service.initialize()
        val original = service.state.value.settings
        val passwordStore = BackupPasswordStore(context)
        val originalPassword = if (original.encryptedMirror) passwordStore.read() else null
        val password = "fixture-only-password-2026"
        val tree = DocumentsContract.buildTreeDocumentUri("com.yenaly.han1meviewer.backup.test.documents", "root")
        val folder = requireNotNull(DocumentFile.fromTreeUri(context, tree))
        val versions = mutableListOf<BackupVersion>()
        grantFixture()
        try {
            context.contentResolver.takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            folder.listFiles().forEach { it.delete() }
            service.configure(original.copy(enabled = false, keep = 30, mirrorUri = tree.toString()))
            service.configureCopyPassword(password)
            assertEquals(password, BackupPasswordStore(context).read())
            assertTrue(BackupSettingsStore(context).read().encryptedMirror)
            val keyFile = File(context.noBackupFilesDir, "backup-copy-password.bin")
            assertFalse(keyFile.readBytes().toString(Charsets.UTF_8).contains(password))
            val version = requireNotNull(service.create()).also { versions += it }
            assertFalse(service.state.value.settings.mirrorFailed)
            val copy = folder.listFiles().single()
            assertTrue(copy.name!!.endsWith(".hvbenc"))
            val bytes = context.contentResolver.openInputStream(copy.uri)!!.use { BackupCodec.readBounded(it, EncryptedBackupCodec.MAX_FILE_BYTES) }
            assertTrue(EncryptedBackupCodec.isEncrypted(bytes))
            assertEquals(version.sha256, BackupCodec.digest(EncryptedBackupCodec.decode(bytes, password)))
            val before = AndroidBackupBackend(context).capture().copy(exportedAt = 0)
            val ids = service.state.value.versions.map { it.id }
            try { service.import(copy.uri); fail() } catch (_: BackupPasswordRequiredException) { }
            try { service.import(copy.uri, "wrong-password-2026"); fail() } catch (_: BackupUnlockException) { }
            assertEquals(before, AndroidBackupBackend(context).capture().copy(exportedAt = 0))
            service.refresh()
            assertEquals(ids, service.state.value.versions.map { it.id })
            assertFalse(service.recoveryRequired.value)
            val exported = requireNotNull(folder.createFile("application/octet-stream", "exported.hvbenc"))
            service.export(version.id, exported.uri, password)
            val exportBytes = context.contentResolver.openInputStream(exported.uri)!!.use { BackupCodec.readBounded(it, EncryptedBackupCodec.MAX_FILE_BYTES) }
            assertEquals(version.sha256, BackupCodec.digest(EncryptedBackupCodec.decode(exportBytes, password)))
            val count = folder.listFiles().size
            val currentExport = service.prepareExport(password = password)
            assertTrue(EncryptedBackupCodec.isEncrypted(currentExport))
            service.state.value.versions.filter { it.id !in ids }.forEach { versions += it }
            assertEquals("Manual export must not produce an extra folder copy", count, folder.listFiles().size)
            val saved = keyFile.readBytes()
            try {
                keyFile.writeBytes(byteArrayOf(1, 2, 3))
                val retained = requireNotNull(service.create()).also { versions += it }
                assertTrue(service.state.value.settings.mirrorFailed)
                assertTrue(service.state.value.versions.any { it.id == retained.id })
                assertEquals("Unreadable key must never produce plaintext copy", count, folder.listFiles().size)
            } finally { keyFile.writeBytes(saved) }
            service.configureCopyPassword(null)
            assertFalse(BackupSettingsStore(context).read().encryptedMirror)
            assertFalse(keyFile.exists())
            assertArrayEquals(EncryptedBackupCodec.decode(bytes, password), EncryptedBackupCodec.decode(exportBytes, password))
            requireNotNull(service.create()).also { versions += it }
            assertTrue(folder.listFiles().any { it.name!!.endsWith(".json") })
            assertTrue(copy.exists())
        } finally {
            service.configureCopyPassword(originalPassword)
            service.configure(original)
            versions.distinctBy { it.id }.forEach { service.delete(it.id) }
            folder.listFiles().forEach { it.delete() }
            grantFixture(revoke = true)
        }
    }

    @Test fun independentPortableEncryptionRestoresAllSectionsOnAndroid() = runBlocking {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("encrypted-backup-reference.hvbenc").use { it.readBytes() }
        val portable = BackupCodec.decode(EncryptedBackupCodec.decode(bytes, "fixture-only-中文-password"))
        assertEquals(PreferenceValue.StringValue("portable 中文 日本語"), portable.settings!!["reference"])
        val password = "fixture-中文-password"
        val source = snapshot().copy(appVersionCode = com.yenaly.han1meviewer.BuildConfig.VERSION_CODE,
            appVersionName = com.yenaly.han1meviewer.BuildConfig.VERSION_NAME)
        val encrypted = EncryptedBackupCodec.encrypt(BackupCodec.encode(source), password)
        isolated { backend, _, _ ->
            val directory = File(context.cacheDir, "encrypted-restore-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val repository = BackupRepository(BackupVault(directory), backend)
                repository.import(EncryptedBackupCodec.decode(encrypted, password), 7)
                assertEquals(source, backend.capture().copy(exportedAt = 0))
                assertFalse(repository.list().isEmpty())
            } finally { directory.deleteRecursively() }
        }
    }

}
