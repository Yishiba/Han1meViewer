package com.yenaly.han1meviewer.logic.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AnnouncementLinksTest {
    @Test
    fun retainsReleaseAndMembershipPaths() {
        val release = "https://github.com/Yishiba/Han1meViewer/releases"
        val membership = "https://afdian.com/a/chuishui"
        val text = "更新：$release，会员：$membership。"

        val links = findAnnouncementLinks(text)

        assertEquals(listOf(release, membership), links.map { it.url })
        assertEquals(links.map { it.url }, links.map { text.substring(it.start, it.endExclusive) })
    }

    @Test
    fun retainsQueryAndLeavesSentencePunctuationOutsideLink() {
        val text = "（https://example.com/path/file.apk?version=2），下一条 https://example.org/#help!"

        assertEquals(
            listOf("https://example.com/path/file.apk?version=2", "https://example.org/#help"),
            findAnnouncementLinks(text).map { it.url },
        )
    }
}
