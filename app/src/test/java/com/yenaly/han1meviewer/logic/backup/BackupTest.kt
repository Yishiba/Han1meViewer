package com.yenaly.han1meviewer.logic.backup

import com.yenaly.han1meviewer.logic.entity.download.HanimeDownloadEntity
import com.yenaly.han1meviewer.logic.entity.download.HanimeCategoryCrossRef
import com.yenaly.han1meviewer.logic.state.DownloadState
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class BackupTest {
    @get:Rule val temp = TemporaryFolder()
    private fun payload(value: String = "one") = BackupPayload(settings = mapOf("setting" to PreferenceValue.StringValue(value)))
    private fun video() = HanimeDownloadEntity(coverUrl = "", coverUri = null, title = "Sample", addDate = 1,
        videoCode = "V1", videoUri = "file:///video.mp4", quality = "HD", videoUrl = "", length = 10,
        downloadedLength = 5, state = DownloadState.Downloading, id = 1)
    private class FakeBackend(var value: BackupPayload) : BackupBackend {
        var calls = 0
        var failNext = false
        var failAll = false
        override suspend fun capture() = value
        override suspend fun apply(payload: BackupPayload) {
            calls++
            value = payload
            if (failAll || failNext) { failNext = false; throw IOException("Injected write failure") }
        }
    }

    @Test fun `roundtrip removes credentials but preserves every preference type`() {
        val source = BackupPayload(settings = mapOf(
            "a" to PreferenceValue.BooleanValue(true), "b" to PreferenceValue.FloatValue(1.25f),
            "c" to PreferenceValue.IntValue(2), "d" to PreferenceValue.LongValue(3),
            "e" to PreferenceValue.StringValue("中文 English 日本語"), "f" to PreferenceValue.StringSetValue(setOf("a", "b")),
            "cookie" to PreferenceValue.StringValue("secret"), "cf_cookie" to PreferenceValue.StringValue("secret"),
            "already_login" to PreferenceValue.BooleanValue(true), "saved_user_id" to PreferenceValue.StringValue("secret"),
            "usage_notice_2026_11_20_accepted" to PreferenceValue.BooleanValue(true)))
        val bytes = BackupCodec.encode(source)
        val restored = BackupCodec.decode(bytes)
        assertEquals(setOf("a", "b", "c", "d", "e", "f"), restored.settings!!.keys)
        assertFalse(bytes.toString(Charsets.UTF_8).contains("secret"))
        assertEquals(source.settings!!.filterKeys(BackupCodec::restorableKey), restored.settings)
    }

    @Test fun `legacy nested discriminators migrate without replacing user strings`() {
        val legacyPrefix = "com.yenaly.han1meviewer.logic.BackupManager.PreferenceValue."
        val json = """{"version":1,"settings":{"value":{"type":"${legacyPrefix}StringValue","value":"${legacyPrefix}literal"},"cookie":{"type":"${legacyPrefix}StringValue","value":"secret"}}}"""
        val decoded = BackupCodec.decode(json.toByteArray())
        assertEquals(PreferenceValue.StringValue("${legacyPrefix}literal"), decoded.settings!!["value"])
        assertFalse(decoded.settings.containsKey("cookie"))
    }

    @Test fun `checksum unsupported formats empty files and oversized input are rejected`() {
        val bytes = BackupCodec.encode(payload())
        val envelope = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val damaged = JsonObject(envelope + ("payload" to JsonPrimitive(envelope.getValue("payload").jsonPrimitive.content + " ")))
        assertThrows(Exception::class.java) { BackupCodec.decode(damaged.toString().toByteArray()) }
        assertThrows(Exception::class.java) { BackupCodec.decode("""{"version":99,"settings":{}}""".toByteArray()) }
        assertThrows(Exception::class.java) { BackupCodec.decode("{}".toByteArray()) }
        assertThrows(Exception::class.java) { BackupCodec.decode(byteArrayOf()) }
        assertThrows(Exception::class.java) { BackupCodec.readBounded(byteArrayOf(1, 2, 3).inputStream(), 2) }
    }

    @Test fun `duplicate keys orphaned relations and invalid group references are rejected`() {
        assertThrows(Exception::class.java) { BackupCodec.encode(payload().copy(downloads = listOf(video(), video()))) }
        assertThrows(Exception::class.java) { BackupCodec.encode(payload().copy(downloads = listOf(video().copy(groupId = 999)))) }
        assertThrows(Exception::class.java) { BackupCodec.encode(payload().copy(downloadCategoryCrossRefs = listOf(HanimeCategoryCrossRef(1, 1)))) }
        assertThrows(Exception::class.java) { BackupCodec.encode(BackupPayload(settings = mapOf("app_language" to PreferenceValue.IntValue(1)))) }
        assertThrows(Exception::class.java) { BackupCodec.encode(BackupPayload(settings = mapOf("download_speed_limit" to PreferenceValue.IntValue(999)))) }
    }

    @Test fun `unfinished downloads restore paused and rule catalog corruption remains recoverable`() {
        val source = payload().copy(downloads = listOf(video(), video().copy(id = 2, state = DownloadState.Finished)),
            settings = mapOf("media_library_smart_collections_v1" to PreferenceValue.StringValue("damaged")))
        val restored = BackupCodec.decode(BackupCodec.encode(source))
        assertEquals(listOf(DownloadState.Paused, DownloadState.Finished), restored.downloads!!.map { it.state })
        assertEquals(source.settings, restored.settings)
    }

    @Test fun `retention keeps newest ordinary versions and latest protected snapshot`() {
        val vault = BackupVault(temp.newFolder())
        val ordinary = (1..5).map { vault.write(payload("$it"), BackupKind.MANUAL, it.toLong()) }
        vault.write(payload(), BackupKind.BEFORE_RESTORE, 6)
        val safety = vault.write(payload(), BackupKind.BEFORE_RESTORE, 7)
        vault.prune(3)
        assertEquals((ordinary.takeLast(3).map { it.id } + safety.id).toSet(), vault.list().map { it.id }.toSet())
        assertThrows(Exception::class.java) { vault.delete(safety.id) }
        assertThrows(Exception::class.java) { vault.read("../../outside") }
    }

    @Test fun `unfinished files and damaged payloads never replace a valid version`() {
        val folder = temp.newFolder()
        val vault = BackupVault(folder)
        val good = vault.write(payload(), BackupKind.MANUAL)
        File(folder, "unfinished.partial").writeText("truncated")
        assertEquals(listOf(good), vault.list())
        assertEquals(payload(), BackupCodec.decode(vault.read(good.id)))
        val damaged = vault.write(payload("other"), BackupKind.MANUAL)
        File(folder, "${damaged.id}.hvb").appendText("corruption")
        assertThrows(Exception::class.java) { vault.read(damaged.id) }
        assertEquals(payload(), BackupCodec.decode(vault.read(good.id)))
    }

    @Test fun `validation failure leaves backend untouched and creates no safety file`() = runBlocking {
        val vault = BackupVault(temp.newFolder())
        val backend = FakeBackend(payload())
        val repository = BackupRepository(vault, backend)
        try { repository.import("invalid".toByteArray(), 3); fail() } catch (_: Exception) { }
        assertEquals(0, backend.calls)
        assertTrue(vault.list().isEmpty())
        assertFalse(vault.hasJournal())
    }

    @Test fun `successful restore keeps previous state in a reusable safety version`() = runBlocking {
        val vault = BackupVault(temp.newFolder())
        val backend = FakeBackend(payload("before"))
        val repository = BackupRepository(vault, backend)
        repository.import(BackupCodec.encode(payload("after")), 3)
        assertEquals(payload("after"), backend.value)
        val safety = vault.list().single()
        assertEquals(BackupKind.BEFORE_RESTORE, safety.kind)
        assertEquals(payload("before"), BackupCodec.decode(vault.read(safety.id)))
        assertFalse(vault.hasJournal())
        repository.restoreVersion(safety.id, 3)
        assertEquals(payload("before"), backend.value)
    }

    @Test fun `mid-restore failure rolls back and rollback failure retains journal for restart`() = runBlocking {
        val vault = BackupVault(temp.newFolder())
        val backend = FakeBackend(payload("before"))
        val repository = BackupRepository(vault, backend)
        backend.failNext = true
        try { repository.import(BackupCodec.encode(payload("after")), 3); fail() } catch (_: IOException) { }
        assertEquals(payload("before"), backend.value)
        assertFalse(vault.hasJournal())
        backend.failAll = true
        try { repository.import(BackupCodec.encode(payload("after")), 3); fail() } catch (_: IOException) { }
        assertTrue(vault.hasJournal())
        backend.failAll = false
        backend.value = payload("partially written")
        assertTrue(BackupRepository(vault, backend).recover())
        assertEquals(payload("before"), backend.value)
        assertFalse(vault.hasJournal())
    }

    @Test fun `journal survives recreation and blocks new backup before recovery`() = runBlocking {
        val folder = temp.newFolder()
        val vault = BackupVault(folder)
        val safety = vault.write(payload("before"), BackupKind.BEFORE_RESTORE)
        vault.beginRestore(safety.id)
        val backend = FakeBackend(payload("partial"))
        val repository = BackupRepository(BackupVault(folder), backend)
        try { repository.create(BackupKind.MANUAL, 3); fail() } catch (_: IllegalStateException) { }
        assertTrue(repository.recover())
        assertEquals(payload("before"), backend.value)
        assertFalse(repository.recover())
    }

    @Test fun `download gate allows concurrency refuses busy restore and releases on failure`() = runBlocking {
        val gate = BackupAccessGate()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val download = launch { gate.download { entered.complete(Unit); finish.await() } }
        entered.await()
        assertEquals(2, gate.download { 2 })
        try { gate.restore { fail() }; fail() } catch (_: BackupBusyException) { }
        finish.complete(Unit); download.join()
        try { gate.restore { throw IOException("failure") } } catch (_: IOException) { }
        assertEquals(3, gate.download { 3 })
        gate.restore { }
    }

    @Test fun `new download waits for restore and canceled download releases its slot`() = runBlocking {
        val gate = BackupAccessGate()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var downloaded = false
        val restore = launch { gate.restore { entered.complete(Unit); finish.await() } }
        entered.await()
        val download = launch(start = CoroutineStart.UNDISPATCHED) { gate.download { downloaded = true } }
        assertFalse(downloaded)
        finish.complete(Unit)
        restore.join(); download.join()
        assertTrue(downloaded)
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { gate.download { awaitCancellation() } }
        cancelled.cancelAndJoin()
        gate.restore { }
    }
    @Test fun `custom metadata apply failure still rolls back full snapshot`() = runBlocking {
        val vault = BackupVault(temp.newFolder())
        val backend = FakeBackend(payload("before"))
        val repository = BackupRepository(vault, backend)
        try {
            repository.importApplying(BackupCodec.encode(payload("after")), 3) {
                backend.value = it
                throw IOException("Failure after metadata write")
            }
            fail()
        } catch (_: IOException) { }
        assertEquals(payload("before"), backend.value)
        assertFalse(vault.hasJournal())
    }

}
