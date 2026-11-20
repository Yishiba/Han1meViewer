package com.yenaly.han1meviewer.logic.library

import com.yenaly.han1meviewer.logic.entity.download.HanimeDownloadEntity
import com.yenaly.han1meviewer.logic.entity.download.VideoWithCategories
import com.yenaly.han1meviewer.logic.state.DownloadState
import org.junit.Assert.*
import org.junit.Test

class MediaLibraryRuleTest {
    private val now = 1_800_000_000_000L
    private fun item(id: Int, title: String = "Alpha", code: String = "V$id", group: Int = 1,
                     quality: String = "1080p", mib: Long = 100, days: Int = 1,
                     state: DownloadState = DownloadState.Finished) = VideoWithCategories(
        HanimeDownloadEntity(
            id = id, groupId = group, title = title, videoCode = code, quality = quality,
            addDate = now - days * MediaLibraryRule.DAY_MILLIS, length = mib * MediaLibraryRule.MIB,
            downloadedLength = mib * MediaLibraryRule.MIB, state = state,
            coverUrl = "", coverUri = null, videoUri = "file:///test/$id.mp4", videoUrl = "",
        ), emptyList(),
    )

    @Test fun `fields combine with AND while selected groups and qualities use OR`() {
        val items = listOf(item(1), item(2, group = 2, quality = "720p"), item(3, group = 3),
            item(4, title = "Beta"), item(5, days = 31), item(6, mib = 201), item(7, quality = "480p"))
        val rule = MediaLibraryRule(keyword = "ALPHA", groupIds = setOf(1, 2), qualities = setOf("1080p", "720p"),
            minSizeMiB = 100, maxSizeMiB = 200, recentDays = 30)
        assertEquals(setOf(1, 2), rule.apply(items, now).map { it.video.id }.toSet())
    }

    @Test fun `code search and exclusion are case insensitive and whitespace trimmed`() {
        val rule = MediaLibraryRule(keyword = " v12 ", excludedKeyword = " beta ")
        assertEquals(listOf(1), rule.apply(listOf(item(1, code = "V123"), item(2, code = "V124", title = "BETA")), now).map { it.video.id })
    }

    @Test fun `size and day boundaries are inclusive and future entries excluded`() {
        val rule = MediaLibraryRule(minSizeMiB = 100, maxSizeMiB = 200, recentDays = 7)
        assertEquals(setOf(1, 2), rule.apply(listOf(item(1, mib = 100, days = 7), item(2, mib = 200, days = 0),
            item(3, mib = 99), item(4, mib = 201), item(5, days = 8), item(6, days = -1)), now).map { it.video.id }.toSet())
    }

    @Test fun `only finished records appear even when downloading byte count equals length`() {
        assertEquals(listOf(1), MediaLibraryRule().apply(listOf(item(1), item(2, state = DownloadState.Paused)), now).map { it.video.id })
    }

    @Test fun `missing groups and qualities do not silently broaden a saved rule`() {
        assertTrue(MediaLibraryRule(groupIds = setOf(999)).apply(listOf(item(1)), now).isEmpty())
        assertTrue(MediaLibraryRule(qualities = setOf("4K")).apply(listOf(item(1)), now).isEmpty())
    }

    @Test fun `all five sort orders work with deterministic ties`() {
        val items = listOf(item(1, title = "Zulu", mib = 200, days = 3), item(2, title = "Alpha", mib = 50, days = 1))
        for (sort in listOf(LibrarySort.NEWEST, LibrarySort.TITLE, LibrarySort.SMALLEST)) {
            assertEquals(listOf(2, 1), MediaLibraryRule(sort = sort).apply(items, now).map { it.video.id })
        }
        for (sort in listOf(LibrarySort.OLDEST, LibrarySort.LARGEST)) {
            assertEquals(listOf(1, 2), MediaLibraryRule(sort = sort).apply(items, now).map { it.video.id })
        }
        assertEquals(listOf(2, 1), MediaLibraryRule().apply(listOf(item(1), item(2)), now).map { it.video.id })
    }

    @Test fun `saved collections recalculate after new downloads moves and time changes`() {
        val saved = SmartCollection("id", "Recent HD", MediaLibraryRule(groupIds = setOf(1), recentDays = 7))
        assertEquals(1, saved.rule.apply(listOf(item(1)), now).size)
        assertEquals(2, saved.rule.apply(listOf(item(1), item(2)), now).size)
        assertTrue(saved.rule.apply(listOf(item(1, group = 2)), now).isEmpty())
        assertTrue(saved.rule.apply(listOf(item(1)), now + 8 * MediaLibraryRule.DAY_MILLIS).isEmpty())
    }

    @Test fun `invalid numeric input including overflow cannot become an unrestricted rule`() {
        val rule = MediaLibraryRule()
        assertNotNull(rule.withInputBounds("", "", ""))
        for ((min, max, days) in listOf(Triple("-1", "", ""), Triple("100", "10", ""),
            Triple("1.5", "", ""), Triple("999999999999999999999999", "", ""),
            Triple((MediaLibraryRule.MAX_SIZE_MIB + 1).toString(), "", ""),
            Triple("", "", "0"), Triple("", "", "36501"))) {
            assertNull(rule.withInputBounds(min, max, days))
        }
    }

    @Test fun `complete rules round trip through saved catalog format`() {
        val rule = MediaLibraryRule(keyword = "Alpha", excludedKeyword = "Beta", groupIds = setOf(1, 2),
            qualities = setOf("1080p"), minSizeMiB = 100, maxSizeMiB = 200, recentDays = 7, sort = LibrarySort.LARGEST)
        val collections = listOf(SmartCollection("a", "Collection", rule))
        assertEquals(collections, MediaLibraryCodec.decodeCollections(MediaLibraryCodec.encodeCollections(collections)))
        assertEquals(rule, MediaLibraryCodec.decodeRule(MediaLibraryCodec.encodeRule(rule)))
    }

    @Test fun `malformed future and duplicate catalogs are rejected rather than cleared`() {
        assertThrows(Exception::class.java) { MediaLibraryCodec.decodeCollections("broken") }
        assertThrows(Exception::class.java) { MediaLibraryCodec.decodeCollections("{\"version\":99,\"collections\":[]}") }
        assertThrows(Exception::class.java) {
            MediaLibraryCodec.encodeCollections(listOf(SmartCollection("a", "Same", MediaLibraryRule()), SmartCollection("b", " same ", MediaLibraryRule())))
        }
        assertTrue(MediaLibraryCodec.decodeCollections(null).isEmpty())
    }
}
