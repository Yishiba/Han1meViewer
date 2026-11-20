package com.yenaly.han1meviewer.ui.screen.settings.remote

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import com.yenaly.han1meviewer.logic.dao.*
import com.yenaly.han1meviewer.logic.entity.download.*
import com.yenaly.han1meviewer.logic.state.DownloadState
import androidx.media3.datasource.DataSpec
import androidx.test.platform.app.InstrumentationRegistry
import com.yenaly.han1meviewer.logic.backup.*
import com.yenaly.han1meviewer.logic.remote.*
import com.yenaly.han1meviewer.logic.entity.WatchHistoryEntity
import com.yenaly.han1meviewer.logic.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RemoteSyncDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Backend(var value: BackupPayload) : BackupBackend {
        override suspend fun capture() = value
        override suspend fun apply(payload: BackupPayload) { value = payload }
    }
    private fun storage(folder: File) = RemoteStore(object : ContextWrapper(context) {
        override fun getNoBackupFilesDir(): File = folder.apply { mkdirs() }
    })
    @Test fun twoDevicesExchangeEncryptedProgressAndDeletionWithConflictAndStalePreviewProtection() = runBlocking {
        val root = File(context.cacheDir, "dav-sync-${UUID.randomUUID()}").apply { mkdirs() }
        DavTlsFixture().use { server ->
            try {
                val stores = listOf(storage(File(root, "A")), storage(File(root, "B")))
                val profile = DavProfile(server.root, "user", "password", "shared-test-passphrase")
                stores.forEach { it.saveProfile(profile) }
                stores[0].saveProgress(RemoteProgress(server.root + "sample.mp4", "Sample", 1234, 1))
                val initial = BackupPayload(settings = mapOf("theme_color" to PreferenceValue.StringValue("blue"),
                    SmartCollectionStore.KEY to PreferenceValue.StringValue(MediaLibraryCodec.encodeCollections(listOf(SmartCollection("c", "Collection", MediaLibraryRule()))))),
                    watchHistories = listOf(WatchHistoryEntity("", "Title", 1, 2, "V1", 500, 1)))
                val backends = listOf(Backend(initial), Backend(BackupPayload(settings = emptyMap(), watchHistories = emptyList())))
                val services = stores.mapIndexed { index, store ->
                    val backend = backends[index]
                    val repo = BackupRepository(BackupVault(File(root, "vault$index")), backend)
                    RemoteSyncService(context, store, backend, { p -> WebDavClient(p.root, p.username, p.password, server.client) },
                        { expected, replacement, publish ->
                            require(backend.value.copy(exportedAt = 0) == expected)
                            publish()
                            repo.import(BackupCodec.encode(replacement), 3)
                        })
                }
                services[0].apply(services[0].preview(true))
                assertFalse(server.document!!.toString(Charsets.UTF_8).contains("Title"))
                services[1].apply(services[1].preview(true))
                assertEquals(1234L, stores[1].read().progress[server.root + "sample.mp4"]?.position)
                assertEquals(500L, backends[1].value.watchHistories!!.single().progress)
                backends[0].value = backends[0].value.copy(watchHistories = backends[0].value.watchHistories!!.map { it.copy(progress = 700) })
                backends[1].value = backends[1].value.copy(watchHistories = backends[1].value.watchHistories!!.map { it.copy(progress = 900) })
                services[0].apply(services[0].preview(true))
                val conflict = services[1].preview(true)
                assertEquals(1, conflict.merged.conflicts)
                services[1].apply(conflict)
                assertEquals(700L, backends[1].value.watchHistories!!.single().progress)
                val stale = services[0].preview(true)
                services[1].apply(services[1].preview(true))
                try { services[0].apply(stale); fail("Stale ETag must fail") } catch (_: DavConflictException) { }
                backends[0].value = backends[0].value.copy(settings = backends[0].value.settings!! +
                    (SmartCollectionStore.KEY to PreferenceValue.StringValue(MediaLibraryCodec.encodeCollections(emptyList()))))
                services[0].apply(services[0].preview(true))
                services[1].apply(services[1].preview(true))
                assertTrue(MediaLibraryCodec.decodeCollections((backends[1].value.settings!![SmartCollectionStore.KEY] as PreferenceValue.StringValue).value).isEmpty())
                val localBytes = File(root, "A/remote-library.bin").readBytes().toString(Charsets.UTF_8)
                assertFalse(localBytes.contains("password")); assertFalse(localBytes.contains("shared-test-passphrase"))
                assertEquals(profile, storage(File(root, "A")).read().profile)
                stores[1].saveProfile(profile.copy(syncPassphrase = "different-passphrase"))
                val before = backends[1].value
                try { services[1].preview(true); fail("Wrong passphrase must fail") } catch (_: Exception) { }
                assertEquals(before, backends[1].value)
            } finally { root.deleteRecursively() }
        }
    }
    @Test fun localEditsDuringPublishingAreNotOverwrittenAndForgottenProfilesRejectLateProgress() = runBlocking {
        val backend = AndroidBackupBackend(context)
        val expected = backend.capture().copy(exportedAt = 0)
        val probe = "remote-race-${UUID.randomUUID()}"
        val prefs = com.yenaly.han1meviewer.Preferences.preferenceSp
        try {
            try {
                BackupService.get(context).applySynchronizedMetadata(expected, expected) {
                    check(prefs.edit().putString(probe, "new local edit").commit())
                }
                fail("Changed local state must reject the old preview")
            } catch (_: IllegalArgumentException) { }
            assertEquals("new local edit", prefs.getString(probe, null))
        } finally { prefs.edit().remove(probe).commit() }
        val folder = File(context.cacheDir, "dav-forget-${UUID.randomUUID()}")
        try {
            val store = storage(folder)
            val profile = DavProfile("https://example.com/dav/", "user", "password")
            store.saveProfile(profile)
            store.clear()
            store.saveProgress(RemoteProgress("https://example.com/dav/a.mp4", "Sample", 10, 1), profile)
            assertFalse(File(folder, "remote-library.bin").exists())
        } finally { folder.deleteRecursively() }
    }
    @Test fun authenticatedRangeStreamingAndRedirectRejectionUseRealHttps() {
        DavTlsFixture().use { server ->
            val dav = WebDavClient(server.root, "user", "password", server.client)
            assertEquals("sample.mp4", dav.list().single().name)
            server.directoryXml = "<!DOCTYPE root [<!ENTITY x SYSTEM 'file:///private'>]><root>&x;</root>".toByteArray()
            assertThrows(Exception::class.java) { dav.list() }
            server.directoryXml = "<!DOCTYPE root><root/>".toByteArray(Charsets.UTF_16)
            assertThrows(Exception::class.java) { dav.list() }
            val source = DavMediaDataSource(dav)
            try {
                assertEquals(10L, source.open(DataSpec.Builder().setUri(Uri.parse(server.root + "sample.mp4")).setPosition(10).setLength(10).build()))
                val bytes = ByteArray(10)
                assertEquals(10, source.read(bytes, 0, 10))
                assertArrayEquals(server.media.copyOfRange(10, 20), bytes)
                assertEquals(-1, source.read(bytes, 0, 10))
            } finally { source.close() }
            val before = server.requests
            try { source.open(DataSpec(Uri.parse(server.root + "redirect.mp4"))); fail("Redirect must fail") } catch (_: java.io.IOException) { }
            assertEquals(before + 1, server.requests)
        }
    }
    @Test fun synchronizedMetadataPreservesDownloadStateAndExistingGroupReferences() = runBlocking {
        val downloads = Room.inMemoryDatabaseBuilder(context, DownloadDatabase::class.java).build()
        val history = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        val misc = Room.inMemoryDatabaseBuilder(context, MiscellanyDatabase::class.java).build()
        val checkIn = Room.inMemoryDatabaseBuilder(context, CheckInRecordDatabase::class.java).build()
        val name = "sync-preservation-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            downloads.downloadGroupDao.insertDefaultGroup()
            downloads.downloadGroupDao.insert(DownloadGroupEntity("Existing", 1, 7))
            val video = HanimeDownloadEntity(7, "", null, "Local", 1, "V1", "file:///local.mp4", "HD", "", 10, 0, DownloadState.Failed, 1)
            downloads.hanimeDownloadDao.insert(video)
            history.watchHistory.insert(WatchHistoryEntity("", "Title", 1, 2, "V1", 100, 1))
            val backend = AndroidBackupBackend(context, prefs, downloads, history, misc, checkIn,
                com.yenaly.han1meviewer.logic.translation.SearchToolsStore(context, context.getSharedPreferences(name + "-search", Context.MODE_PRIVATE)))
            val before = backend.capture()
            backend.applySynchronized(before.copy(downloadGroups = before.downloadGroups!! + DownloadGroupEntity("Added", 2, 9),
                watchHistories = before.watchHistories!!.map { it.copy(progress = 500) }))
            assertEquals(video, downloads.hanimeDownloadDao.findById(1))
            assertEquals("Added", downloads.downloadGroupDao.getGroupById(9)?.name)
            assertEquals(500L, history.watchHistory.findBy("V1")?.progress)
        } finally {
            listOf(downloads, history, misc, checkIn).forEach { it.close() }
            context.deleteSharedPreferences(name)
            context.deleteSharedPreferences(name + "-search")
        }
    }

}
