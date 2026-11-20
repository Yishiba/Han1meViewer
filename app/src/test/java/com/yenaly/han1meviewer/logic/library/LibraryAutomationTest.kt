package com.yenaly.han1meviewer.logic.library

import com.yenaly.han1meviewer.logic.entity.download.HanimeDownloadEntity
import com.yenaly.han1meviewer.logic.state.DownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LibraryAutomationTest {
    private val now = 1_800_000_000_000L
    private fun video(id: Int, group: Int = 1, uri: String = "file$id", size: Long = 10,
                      state: DownloadState = DownloadState.Finished) = HanimeDownloadEntity(
        id = id, groupId = group, title = "Alpha $id", videoCode = "V$id", quality = "1080p", addDate = now,
        coverUrl = "", coverUri = null, videoUri = uri, videoUrl = "", length = size,
        downloadedLength = size, state = state,
    )
    private fun rule(id: String, target: Int, enabled: Boolean = true, keyword: String = "Alpha") =
        SmartCollection(id, id, MediaLibraryRule(keyword = keyword), enabled, target)

    @Test fun `first matching enabled valid rule wins`() {
        val rules = listOf(rule("disabled", 2, false), rule("missing", 999), rule("first", 3), rule("second", 2))
        val target = AutoOrganizer.target(video(1), rules, setOf(1, 2, 3), now)
        assertEquals(3, target?.targetGroupId)
        assertEquals("first", target?.ruleName)
        assertEquals(2, AutoOrganizer.target(video(1), rules.reversed(), setOf(1, 2, 3), now)?.targetGroupId)
    }

    @Test fun `manual groups unfinished files and nonmatching filters are preserved`() {
        val rules = listOf(rule("rule", 2))
        assertNull(AutoOrganizer.target(video(1, group = 3), rules, setOf(1, 2, 3), now))
        assertNull(AutoOrganizer.target(video(1, state = DownloadState.Downloading), rules, setOf(1, 2), now))
        assertNull(AutoOrganizer.target(video(1), listOf(rule("rule", 2, keyword = "Beta")), setOf(1, 2), now))
        assertNull(AutoOrganizer.target(video(1), listOf(rule("rule", 1)), setOf(1, 2), now))
    }

    @Test fun `preview changes only matched ungrouped records`() {
        val source = listOf(video(1), video(2, group = 3), video(3, state = DownloadState.Paused))
        val preview = AutoOrganizer.preview(source, listOf(rule("rule", 2)), setOf(1, 2, 3), now)
        assertEquals(listOf(1), preview.map { it.recordId })
        assertEquals(listOf(1, 3, 1), source.map { it.groupId })
    }

    @Test fun `old saved rules remain manual and automation survives serialization`() {
        val old = MediaLibraryCodec.decodeCollections("""{"version":1,"collections":[{"id":"a","name":"Legacy","rule":{}}]}""")
        assertFalse(old.single().autoEnabled)
        assertNull(old.single().autoTargetGroupId)
        val rules = listOf(rule("a", 2), rule("b", 3))
        assertEquals(rules, MediaLibraryCodec.decodeCollections(MediaLibraryCodec.encodeCollections(rules)))
        assertThrows(Exception::class.java) { MediaLibraryCodec.encodeCollections(listOf(rule("invalid", 1))) }
    }

    private class FakeFiles : LibraryFileAccess {
        val probes = mutableMapOf<String, FileProbe>()
        val hashes = mutableMapOf<String, String>()
        val calls = mutableListOf<String>()
        var afterHash: (String) -> Unit = {}
        val failures = mutableSetOf<String>()
        override suspend fun probe(uri: String) = probes[uri] ?: FileProbe(FileStatus.UNAVAILABLE)
        override suspend fun sha256(uri: String, expectedBytes: Long): String {
            calls += uri
            if (uri in failures) throw IOException("unreadable")
            afterHash(uri)
            return hashes.getValue(uri)
        }
        fun add(uri: String, bytes: Long, hash: String, identity: String = uri) {
            probes[uri] = FileProbe(FileStatus.AVAILABLE, bytes, identity, 123)
            hashes[uri] = hash
        }
    }

    @Test fun `duplicates require equal actual size and full digest not equal names or metadata`() = runBlocking {
        val files = FakeFiles().apply {
            add("a", 100, "same"); add("b", 100, "same"); add("c", 100, "different"); add("d", 101, "same")
        }
        val report = LibraryInspector(files).scan(listOf(video(1, uri = "a", size = 1), video(2, uri = "b", size = 2),
            video(3, uri = "c"), video(4, uri = "d")), 1)
        assertEquals(listOf(1, 2), report.duplicates.single().files.map { it.video.id })
        assertFalse(report.duplicates.single().sameReference)
        assertEquals(listOf("a", "b", "c"), files.calls)
    }

    @Test fun `aliases are duplicate references and do not multiply hash or large file work`() = runBlocking {
        val files = FakeFiles().apply {
            add("a", MediaLibraryRule.MIB, "same", "one-file")
            add("alias", MediaLibraryRule.MIB, "same", "one-file")
        }
        val report = LibraryInspector(files).scan(listOf(video(1, uri = "a"), video(2, uri = "alias")), 1)
        assertTrue(report.duplicates.single().sameReference)
        assertTrue(files.calls.isEmpty())
        assertEquals(1, report.largeFiles.size)
    }

    @Test fun `unavailable missing empty and unfinished records are distinct`() = runBlocking {
        val files = FakeFiles().apply {
            probes["missing"] = FileProbe(FileStatus.MISSING)
            probes["permission"] = FileProbe(FileStatus.UNAVAILABLE)
            add("empty", 0, "empty")
        }
        val report = LibraryInspector(files).scan(listOf(video(1, uri = "missing"), video(2, uri = "permission"),
            video(3, uri = "empty"), video(4, state = DownloadState.Downloading)), 1)
        assertEquals(3, report.source.size)
        assertEquals(listOf(FileStatus.MISSING, FileStatus.UNAVAILABLE, FileStatus.EMPTY), report.issues.map { it.probe.status })
        assertTrue(files.calls.isEmpty())
    }

    @Test fun `hash read failures and changing files do not produce false duplicates`() = runBlocking {
        val files = FakeFiles().apply {
            add("a", 100, "same"); add("b", 100, "same"); add("c", 100, "same")
            failures += "c"
            afterHash = { if (it == "b") probes[it] = probes.getValue(it).copy(modified = 456) }
        }
        val report = LibraryInspector(files).scan(listOf(video(1, uri = "a"), video(2, uri = "b"), video(3, uri = "c")), 1)
        assertTrue(report.duplicates.isEmpty())
        assertEquals(setOf(FileStatus.CHANGED, FileStatus.UNAVAILABLE), report.issues.map { it.probe.status }.toSet())
    }

    @Test fun `large threshold uses actual file size and results are descending`() = runBlocking {
        val files = FakeFiles().apply { add("a", 2 * MediaLibraryRule.MIB, "a"); add("b", MediaLibraryRule.MIB, "b") }
        val report = LibraryInspector(files).scan(listOf(video(2, uri = "b"), video(1, uri = "a")), 1)
        assertEquals(listOf(1, 2), report.largeFiles.map { it.video.id })
        assertEquals(1L, report.thresholdMiB)
    }

    @Test fun `inspection cancellation propagates instead of looking like a bad file`() = runBlocking {
        val files = FakeFiles().apply {
            add("a", 100, "same"); add("b", 100, "same")
            afterHash = { throw CancellationException("cancelled") }
        }
        try {
            LibraryInspector(files).scan(listOf(video(1, uri = "a"), video(2, uri = "b")), 1)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { assertEquals(listOf("a"), files.calls) }
    }
}
