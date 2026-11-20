package com.yenaly.han1meviewer.ui.screen.home.download

import androidx.test.platform.app.InstrumentationRegistry
import com.yenaly.han1meviewer.util.SafFileManager
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class LibraryFileDeletionTest {
    @Test fun deletingOneQualityLeavesOtherQualityCoverAndMetadataIntact() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "library-deletion-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val selected = File(folder, "video-720p.mp4").apply { writeText("selected") }
            val other = File(folder, "video-1080p.mp4").apply { writeText("keep") }
            val cover = File(folder, "cover.png").apply { writeText("cover") }
            val info = File(folder, "info.json").apply { writeText("{}") }
            val item = libraryItem(1, quality = "720p", uri = selected.toURI().toString())
            assertTrue(SafFileManager.deleteDownloadedFile(context, item.video))
            assertFalse(selected.exists())
            assertEquals("keep", other.readText())
            assertTrue(cover.exists())
            assertTrue(info.exists())
            assertFalse(SafFileManager.deleteDownloadedFile(context, item.video))
            assertFalse(SafFileManager.deleteDownloadedFile(context, item.video.copy(videoUri = "https://invalid/file.mp4")))
        } finally { folder.deleteRecursively() }
    }
}
