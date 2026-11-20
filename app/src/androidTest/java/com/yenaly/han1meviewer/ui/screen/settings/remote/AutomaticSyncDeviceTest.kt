package com.yenaly.han1meviewer.ui.screen.settings.remote

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import com.yenaly.han1meviewer.logic.backup.*
import com.yenaly.han1meviewer.logic.remote.*
import com.yenaly.han1meviewer.logic.translation.*
import com.yenaly.han1meviewer.util.await
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AutomaticSyncDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Backend(var value: BackupPayload) : BackupBackend {
        var applies = 0
        override suspend fun capture() = value
        override suspend fun apply(payload: BackupPayload) { applies++; value = payload }
    }
    private suspend fun fixture(block: suspend (DavTlsFixture, List<RemoteStore>, List<Backend>, List<RemoteSyncService>, List<AutomaticSyncSettingsStore>) -> Unit) {
        val root = File(context.cacheDir, "auto-sync-${UUID.randomUUID()}").apply { mkdirs() }
        val names = (0..1).map { "automatic-sync-test-${UUID.randomUUID()}" }
        DavTlsFixture().use { server ->
            try {
                val stores = (0..1).map { index -> RemoteStore(object : ContextWrapper(context) {
                    override fun getNoBackupFilesDir() = File(root, "$index").apply { mkdirs() }
                }).apply { saveProfile(DavProfile(server.root, "user", "password", "fixture-shared-passphrase")) } }
                val backends = (0..1).map { Backend(BackupPayload(version = 3, settings = emptyMap(), searchTools = SearchToolsData())) }
                val services = stores.mapIndexed { index, store ->
                    val backend = backends[index]
                    val repo = BackupRepository(BackupVault(File(root, "vault$index")), backend)
                    RemoteSyncService(context, store, backend, { p -> WebDavClient(p.root, p.username, p.password, server.client) },
                        { expected, replacement, publish ->
                            if (backend.value.copy(exportedAt = 0) != expected) throw SyncLocalChangedException()
                            publish()
                            repo.import(BackupCodec.encode(replacement), 3)
                        })
                }
                val configs = names.map { AutomaticSyncSettingsStore(context, context.getSharedPreferences(it, Context.MODE_PRIVATE)) }
                configs.forEach { it.update { value -> value.copy(enabled = true, generation = "fixture") } }
                block(server, stores, backends, services, configs)
            } finally { root.deleteRecursively(); names.forEach(context::deleteSharedPreferences) }
        }
    }
    @Test fun unattendedSyncExchangesSearchEntriesAndDeletionsAndSkipsUnchangedWrites() = runBlocking {
        fixture { server, _, backends, services, configs ->
            backends[0].value = backends[0].value.copy(searchTools = SearchToolsData(listOf(TranslationEntry("中文", "日文")), listOf(TranslationEntry("English", "日本語", 42))))
            val runners = services.mapIndexed { i, service -> AutomaticSyncRunner(configs[i], service) }
            assertEquals(AutomaticRunResult.COMPLETE, runners[0].run("fixture"))
            assertEquals(AutomaticRunResult.COMPLETE, runners[1].run("fixture"))
            assertEquals(backends[0].value.searchTools, backends[1].value.searchTools)
            assertFalse(server.document!!.toString(Charsets.UTF_8).contains("English"))
            val revision = server.revision
            val applies = backends[1].applies
            assertEquals(AutomaticRunResult.COMPLETE, runners[1].run("fixture"))
            assertEquals(revision, server.revision)
            assertEquals(applies, backends[1].applies)
            backends[0].value = backends[0].value.copy(searchTools = SearchToolsData())
            runners[0].run("fixture"); runners[1].run("fixture")
            assertEquals(SearchToolsData(), backends[1].value.searchTools)
            assertEquals(AutomaticSyncStatus.SUCCESS, configs[1].read().status)
            assertTrue(configs[1].read().lastSuccess > 0)
        }
    }
    @Test fun conflictingAliasesPauseWithoutPublishingOrChangingEitherSide() = runBlocking {
        fixture { server, stores, backends, services, configs ->
            fun data(text: String) = SearchToolsData(aliases = listOf(TranslationEntry("same", text)))
            backends[0].value = backends[0].value.copy(searchTools = data("one"))
            services[0].apply(services[0].preview(true)); services[1].apply(services[1].preview(true))
            backends[0].value = backends[0].value.copy(searchTools = data("remote"))
            backends[1].value = backends[1].value.copy(searchTools = data("local"))
            services[0].automatic()
            val remote = server.document!!.clone()
            val before = backends[1].value
            val runner = AutomaticSyncRunner(configs[1], services[1])
            runner.run("fixture")
            assertEquals(AutomaticSyncStatus.CONFLICT, configs[1].read().status)
            assertArrayEquals(remote, server.document)
            assertEquals(before, backends[1].value)
            val requests = server.requests
            runner.run("fixture")
            assertEquals(requests, server.requests)
            services[1].apply(services[1].preview(true))
            val scheduler = AutomaticSyncScheduler(context, configs[1], stores[1])
            try {
                scheduler.manualSuccess()
                assertNotEquals("fixture", configs[1].read().generation)
                runner.run(configs[1].read().generation)
                assertEquals(AutomaticSyncStatus.SUCCESS, configs[1].read().status)
                assertEquals(data("remote"), backends[1].value.searchTools)
            } finally {
                scheduler.configure(false, 60, true)
                AutomaticSyncScheduler(context).initialize()
            }
        }
    }
    @Test fun disabledOrStaleGenerationDoesNoNetworkAndDisablingDuringPreviewPreventsPublish() = runBlocking {
        fixture { server, _, _, services, configs ->
            val runner = AutomaticSyncRunner(configs[0], services[0])
            assertEquals(AutomaticRunResult.COMPLETE, runner.run("old-token"))
            assertEquals(0, server.requests)
            configs[0].update { it.copy(enabled = false) }
            runner.run("fixture")
            assertEquals(0, server.requests)
            configs[0].update { it.copy(enabled = true) }
            var checks = 0
            try {
                services[0].automatic { if (++checks == 2) throw AutomaticSyncStopped() }
                fail()
            } catch (_: AutomaticSyncStopped) { }
            assertNull(server.document)
        }
    }
    @Test fun badCredentialsAndInvalidPassphrasePauseInsteadOfRepeatedWrites() = runBlocking {
        fixture { server, stores, backends, services, configs ->
            stores[0].saveProfile(stores[0].read().profile.copy(password = "wrong"))
            val before = backends[0].value
            val runner = AutomaticSyncRunner(configs[0], services[0])
            runner.run("fixture")
            assertEquals(AutomaticSyncStatus.AUTH, configs[0].read().status)
            assertEquals(before, backends[0].value)
            assertNull(server.document)
            services[1].automatic()
            stores[0].saveProfile(stores[0].read().profile.copy(password = "password", syncPassphrase = "wrong-shared-passphrase"))
            configs[0].update { it.copy(status = AutomaticSyncStatus.IDLE) }
            runner.run("fixture")
            assertEquals(AutomaticSyncStatus.INVALID, configs[0].read().status)
            assertEquals(before, backends[0].value)
        }
    }
    @Test fun periodicWorkPersistsConstraintsUpdatesAndCanBeCancelled() = runBlocking {
        fixture { _, stores, _, _, configs ->
            val manager = WorkManager.getInstance(context)
            val scheduler = AutomaticSyncScheduler(context, configs[0], stores[0])
            try {
                scheduler.configure(true, 15, true)
                var tasks = manager.getWorkInfosForUniqueWork(AutomaticSyncScheduler.WORK_NAME).await().filter { !it.state.isFinished }
                assertEquals(1, tasks.size)
                assertEquals(NetworkType.UNMETERED, tasks.single().constraints.requiredNetworkType)
                assertTrue(tasks.single().constraints.requiresBatteryNotLow())
                scheduler.configure(true, 360, false)
                tasks = manager.getWorkInfosForUniqueWork(AutomaticSyncScheduler.WORK_NAME).await().filter { !it.state.isFinished }
                assertEquals(1, tasks.size)
                assertEquals(NetworkType.CONNECTED, tasks.single().constraints.requiredNetworkType)
                assertEquals(360, configs[0].read().minutes)
                assertEquals(java.util.concurrent.TimeUnit.MINUTES.toMillis(360), tasks.single().periodicityInfo!!.repeatIntervalMillis)
                scheduler.initialize()
                assertEquals(1, manager.getWorkInfosForUniqueWork(AutomaticSyncScheduler.WORK_NAME).await().count { !it.state.isFinished })
                scheduler.configure(false, 60, true)
                assertTrue(manager.getWorkInfosForUniqueWork(AutomaticSyncScheduler.WORK_NAME).await().all { it.state.isFinished })
            } finally {
                scheduler.configure(false, 60, true)
                AutomaticSyncScheduler(context).initialize()
            }
        }
    }
    @Test fun busyRestoreAndTransientServerErrorsRetryWithoutWritingOrMutatingLocalState() = runBlocking {
        fixture { server, stores, backends, _, configs ->
            val service = RemoteSyncService(context, stores[0], backends[0],
                { p -> WebDavClient(p.root, p.username, p.password, server.client) },
                { _, _, _ -> throw BackupBusyException() })
            val runner = AutomaticSyncRunner(configs[0], service)
            val before = backends[0].value
            assertEquals(AutomaticRunResult.RETRY, runner.run("fixture"))
            assertEquals(AutomaticSyncStatus.BUSY, configs[0].read().status)
            assertNull(server.document)
            assertEquals(before, backends[0].value)
            assertEquals(AutomaticRunResult.COMPLETE, runner.run("fixture", 3))
            server.getStatus = 503
            assertEquals(AutomaticRunResult.RETRY, runner.run("fixture"))
            assertEquals(AutomaticSyncStatus.RETRY, configs[0].read().status)
            assertNull(server.document)
        }
    }

    @Test fun actualWorkManagerWorkerIgnoresExpiredAuthorizationWithoutChangingState() = runBlocking {
        val settings = AutomaticSyncSettingsStore(context)
        val before = settings.read()
        val manager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<AutomaticSyncWorker>()
            .setInputData(workDataOf(AutomaticSyncScheduler.GENERATION to UUID.randomUUID().toString())).build()
        try {
            manager.enqueue(request).await()
            val result = withTimeout(30000) {
                var info = manager.getWorkInfoById(request.id).await()!!
                while (!info.state.isFinished) { delay(100); info = manager.getWorkInfoById(request.id).await()!! }
                info
            }
            assertEquals(WorkInfo.State.SUCCEEDED, result.state)
            assertEquals(before, settings.read())
        } finally { manager.cancelWorkById(request.id).await() }
    }

    @Test fun repeatedUseOnTwoDevicesAdvancesHistoryWithoutPausingAutomaticSync() = runBlocking {
        fixture { _, _, backends, services, configs ->
            fun history(time: Long) = SearchToolsData(history = listOf(TranslationEntry("same", "translation", time)))
            backends[0].value = backends[0].value.copy(searchTools = history(10))
            services[0].apply(services[0].preview(true)); services[1].apply(services[1].preview(true))
            backends[0].value = backends[0].value.copy(searchTools = history(100))
            backends[1].value = backends[1].value.copy(searchTools = history(200))
            val runners = services.mapIndexed { index, service -> AutomaticSyncRunner(configs[index], service) }
            runners[0].run("fixture"); runners[1].run("fixture"); runners[0].run("fixture")
            assertEquals(history(200), backends[0].value.searchTools)
            assertEquals(history(200), backends[1].value.searchTools)
            assertEquals(AutomaticSyncStatus.SUCCESS, configs[1].read().status)
        }
    }

}
