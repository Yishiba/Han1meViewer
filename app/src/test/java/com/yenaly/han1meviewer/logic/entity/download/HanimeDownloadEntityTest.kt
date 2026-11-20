package com.yenaly.han1meviewer.logic.entity.download

import org.junit.Assert.assertEquals
import org.junit.Test

class HanimeDownloadEntityTest {
    @Test
    fun unknownLengthHasZeroProgress() {
        assertEquals(0, download(length = 0, downloadedLength = 0).progress)
        assertEquals(0, download(length = -1, downloadedLength = 0).progress)
    }

    @Test
    fun knownLengthKeepsNormalProgress() {
        assertEquals(50, download(length = 100, downloadedLength = 50).progress)
    }

    private fun download(length: Long, downloadedLength: Long) = HanimeDownloadEntity(
        coverUrl = "", coverUri = null, title = "test", addDate = 0,
        videoCode = "1", videoUri = "video.mp4", quality = "720p", videoUrl = "",
        length = length, downloadedLength = downloadedLength,
    )
}
