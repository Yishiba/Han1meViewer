package com.yenaly.han1meviewer.ui.screen.settings.backup

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.yenaly.han1meviewer.logic.backup.*
import com.yenaly.han1meviewer.logic.dao.*
import com.yenaly.han1meviewer.logic.remote.SyncLocalChangedException
import com.yenaly.han1meviewer.logic.translation.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

class SearchBackupDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun isolated(action: suspend (AndroidBackupBackend, SearchToolsStore, android.content.SharedPreferences, File) -> Unit) {
        val name = "search-backup-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val searchPrefs = context.getSharedPreferences("$name-search", Context.MODE_PRIVATE)
        val search = SearchToolsStore(context, searchPrefs)
        val downloads = Room.inMemoryDatabaseBuilder(context, DownloadDatabase::class.java).build()
        val history = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        val misc = Room.inMemoryDatabaseBuilder(context, MiscellanyDatabase::class.java).build()
        val checkIn = Room.inMemoryDatabaseBuilder(context, CheckInRecordDatabase::class.java).build()
        val root = File(context.cacheDir, name).apply { mkdirs() }
        try { action(AndroidBackupBackend(context, preferences, downloads, history, misc, checkIn, search), search, searchPrefs, root) }
        finally {
            listOf(downloads, history, misc, checkIn).forEach { it.close() }
            context.deleteSharedPreferences(name); context.deleteSharedPreferences("$name-search"); root.deleteRecursively()
        }
    }
    @Test fun searchDataEncryptedRoundtripFailureRollbackAndOldBackupPreservation() = runBlocking {
        isolated { backend, store, _, root ->
            store.saveAlias("中文", "日本語"); store.remember("English", "日文")
            val before = store.read()
            val vault = BackupVault(root)
            var failNext = true
            val repository = BackupRepository(vault, object : BackupBackend {
                override suspend fun capture() = backend.capture()
                override suspend fun apply(payload: BackupPayload) {
                    backend.apply(payload)
                    if (failNext) { failNext = false; throw IOException("Injected after search write") }
                }
            })
            val replacement = BackupPayload(version = 3, searchTools = SearchToolsData())
            try { repository.import(BackupCodec.encode(replacement), 7); fail() } catch (_: IOException) { }
            assertEquals(before, store.read())
            assertEquals(before, BackupCodec.decode(vault.read(vault.list().single().id)).searchTools)
            assertFalse(vault.hasJournal())
            repository.import("""{"version":1,"settings":{}}""".toByteArray(), 7)
            assertEquals(before, store.read())
            val encrypted = EncryptedBackupCodec.encrypt(BackupCodec.encode(replacement), "fixture-backup-password")
            repository.import(EncryptedBackupCodec.decode(encrypted, "fixture-backup-password"), 7)
            assertEquals(SearchToolsData(), store.read())
            assertEquals(3, backend.capture().version)
        }
    }
    @Test fun corruptCatalogCanBeProtectedBeforeRestoringKnownGoodData() = runBlocking {
        isolated { backend, store, prefs, root ->
            prefs.edit().putString("data", "broken-json").commit()
            val vault = BackupVault(root)
            val repository = BackupRepository(vault, backend)
            repository.import(BackupCodec.encode(BackupPayload(version = 3, searchTools = SearchToolsData())), 7)
            assertEquals(SearchToolsData(), store.read())
            val protected = vault.list().single()
            assertEquals("broken-json", BackupCodec.decode(vault.read(protected.id)).searchToolsRaw)
            repository.restoreVersion(protected.id, 7)
            assertEquals("broken-json", prefs.getString("data", null))
            assertThrows(Exception::class.java) { store.read() }
        }
    }
    @Test fun multipleStoreInstancesSerializeEditsAndLegacyHistoryMigratesWithoutLoss() = runBlocking {
        isolated { _, store, prefs, _ ->
            prefs.edit().putString("data", """{"history":[{"original":"old","translated":"旧"},{"original":"older","translated":"前"}]}""").commit()
            assertEquals(listOf(2L, 1L), store.read().history.map { it.usedAt })
            coroutineScope {
                repeat(100) { index -> launch(Dispatchers.IO) {
                    SearchToolsStore(context, prefs).saveAlias("term$index", "訳$index")
                    SearchToolsStore(context, prefs).remember("term$index", "訳$index")
                } }
            }
            assertEquals(100, store.read().aliases.size)
            assertEquals(50, store.read().history.size)
            assertEquals(50, store.read().history.map { it.usedAt }.distinct().size)
        }
    }
    @Test fun writesWaitDuringProtectedSyncAndStaleSearchPreviewIsRejectedBeforePublishing() = runBlocking {
        val store = SearchToolsStore(context)
        val before = store.read()
        val backend = AndroidBackupBackend(context)
        val backups = BackupService.get(context)
        var write: Deferred<Unit>? = null
        try {
            val expected = backend.capture().copy(exportedAt = 0)
            coroutineScope {
                backups.applySynchronizedMetadata(expected, expected) {
                    write = async(Dispatchers.IO) { store.saveAlias("gate-fixture", "待機") }
                    delay(100)
                    assertFalse(write!!.isCompleted)
                }
                write!!.await()
            }
            assertTrue(store.read().aliases.any { it.original == "gate-fixture" })
            var published = false
            try { backups.applySynchronizedMetadata(expected, expected) { published = true }; fail() }
            catch (_: SyncLocalChangedException) { }
            assertFalse(published)
        } finally { write?.cancelAndJoin(); store.replace(before) }
    }
}
