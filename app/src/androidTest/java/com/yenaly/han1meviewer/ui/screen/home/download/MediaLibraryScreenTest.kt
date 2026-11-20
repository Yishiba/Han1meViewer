package com.yenaly.han1meviewer.ui.screen.home.download

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.entity.download.DownloadGroupEntity
import com.yenaly.han1meviewer.logic.entity.download.HanimeDownloadEntity
import com.yenaly.han1meviewer.logic.entity.download.VideoWithCategories
import com.yenaly.han1meviewer.logic.library.SmartCollectionStore
import com.yenaly.han1meviewer.logic.state.DownloadState
import com.yenaly.han1meviewer.ui.screen.home.DownloadScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.io.File

internal fun libraryItem(id: Int, title: String = "Alpha", quality: String = "1080p", uri: String = "file:///unused/$id.mp4") =
    VideoWithCategories(HanimeDownloadEntity(
        id = id, title = title, groupId = 1, videoCode = "V$id", quality = quality,
        coverUrl = "", coverUri = null, videoUri = uri, videoUrl = "", addDate = System.currentTimeMillis(),
        length = 100L * 1024 * 1024, downloadedLength = 100L * 1024 * 1024, state = DownloadState.Finished,
    ), emptyList())

class MediaLibraryScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)
    private fun count(matched: Int, total: Int) = context.getString(R.string.library_count, matched, total)
    private fun capture(name: String) {
        compose.waitForIdle()
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), name).outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }

    @Test fun saveDynamicCollectionRenameAndDeleteWithoutDeletingVideos() {
        val preferences = context.getSharedPreferences("library-ui-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val downloads = MutableStateFlow(listOf(libraryItem(1, quality = "720p"), libraryItem(2, title = "Beta"), libraryItem(3)))
        SmartCollectionStore(preferences).use { store ->
            try {
                compose.setContent {
                    val catalog by store.catalog.collectAsState()
                    MaterialTheme { DownloadScreen(
                        downloadingFlow = flowOf(emptyList()), downloadedFlow = downloads,
                        downloadedGroupsFlow = MutableStateFlow(listOf(DownloadGroupEntity("Group A", orderIndex = 0, id = 1))),
                        collapseDownloadedGroup = true, onBack = {}, onLoadDownloaded = {}, onEvent = {},
                        collectionCatalog = catalog, onSaveCollection = { store.save(it) }, onDeleteCollection = { store.delete(it) },
                    ) }
                }
                compose.onNodeWithText(text(R.string.downloaded)).performClick()
                compose.onNodeWithTag("library.search").performTextReplacement("Alpha")
                compose.onNodeWithText(count(2, 3)).assertIsDisplayed()
                compose.onNodeWithText(text(R.string.library_edit)).performClick()
                compose.onNodeWithTag("library.name").performTextReplacement("Alpha HD")
                compose.onNodeWithTag("library.quality.1080p").performScrollTo().performClick()
                compose.onNodeWithText(text(R.string.library_save)).performClick()
                compose.onNodeWithText("Alpha HD").assertIsDisplayed()
                compose.onNodeWithText(count(1, 3)).assertIsDisplayed()
                compose.runOnIdle { downloads.value = downloads.value + libraryItem(4) }
                compose.onNodeWithText(count(2, 4)).assertIsDisplayed()
                capture("media-library.png")
                val id = store.catalog.value.collections.single().id
                SmartCollectionStore(preferences).use { reloaded -> assertEquals(id, reloaded.catalog.value.collections.single().id) }

                compose.onNodeWithText(text(R.string.library_edit)).performClick()
                capture("media-library-rules.png")
                compose.onNodeWithTag("library.name").performTextReplacement("Renamed")
                compose.onNodeWithText(text(R.string.library_save)).performClick()
                compose.onNodeWithText("Renamed").assertIsDisplayed()
                assertEquals(id, store.catalog.value.collections.single().id)
                compose.onNodeWithText(text(R.string.library_edit)).performClick()
                compose.onNodeWithText(text(R.string.library_delete)).performScrollTo().performClick()
                compose.onNodeWithText(text(R.string.confirm)).performClick()
                compose.onNodeWithText(count(4, 4)).assertIsDisplayed()
                assertTrue(store.catalog.value.collections.isEmpty())
                assertEquals(4, downloads.value.size)
            } finally { preferences.edit().clear().commit() }
        }
    }

    @Test fun noMatchesCanBeResetAndBatchDeleteOnlyIncludesVisibleResults() {
        var deletedIds = emptyList<Int>()
        compose.setContent { MaterialTheme { DownloadScreen(
            downloadingFlow = flowOf(emptyList()),
            downloadedFlow = MutableStateFlow(listOf(libraryItem(1), libraryItem(2, title = "Beta"), libraryItem(3))),
            downloadedGroupsFlow = MutableStateFlow(listOf(DownloadGroupEntity("Group A", orderIndex = 0, id = 1))),
            collapseDownloadedGroup = true, onBack = {}, onLoadDownloaded = {},
            onEvent = { if (it is DownloadEvent.OnBatchDelete) deletedIds = it.videos.map { item -> item.video.id } },
        ) } }
        compose.onNodeWithText(text(R.string.downloaded)).performClick()
        compose.onNodeWithTag("library.search").performTextReplacement("missing")
        compose.onNodeWithText(text(R.string.library_no_matches)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_reset)).performClick()
        compose.onNodeWithText(count(3, 3)).assertIsDisplayed()
        compose.onNodeWithTag("library.search").performTextReplacement("Alpha")
        compose.onNodeWithContentDescription(text(R.string.edit)).performClick()
        compose.onNodeWithText(text(R.string.select_all)).performClick()
        // A new rule clears the old selection and exits batch mode, even with groups collapsed.
        compose.onNodeWithTag("library.search").performTextReplacement("Beta")
        compose.onNodeWithText(text(R.string.select_all)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.edit)).performClick()
        compose.onNodeWithText(text(R.string.select_all)).performClick()
        compose.onNodeWithTag("library.batch.delete").performClick()
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        compose.runOnIdle { assertEquals(listOf(2), deletedIds) }
    }
}
