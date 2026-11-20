package com.yenaly.han1meviewer.ui.screen.home.download

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.entity.download.DownloadGroupEntity
import com.yenaly.han1meviewer.logic.library.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.io.File

class LibraryToolsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)
    private fun capture(name: String) {
        compose.waitForIdle()
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun autoRuleRequiresDestinationAndSavesEnabledConfiguration() {
        var saved: SmartCollection? = null
        compose.setContent { MaterialTheme { MediaLibraryRuleDialog(
            initial = MediaLibraryRule(), collection = null, catalog = CollectionCatalog(),
            groups = listOf(DownloadGroupEntity("Ungrouped", 0, 1), DownloadGroupEntity("HD", 1, 2)),
            qualities = listOf("1080p"), onDismiss = {}, onApply = {},
            onSave = { saved = it; true }, onSaved = {}, onDelete = { true }, onDeleted = {},
        ) } }
        compose.onNodeWithTag("library.name").performTextReplacement("Auto HD")
        compose.onNodeWithTag("library.auto.enabled").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.library_save)).assertIsNotEnabled()
        compose.onNodeWithTag("library.auto.target.2").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.library_save)).performClick()
        compose.runOnIdle {
            assertEquals("Auto HD", saved?.name)
            assertTrue(saved?.autoEnabled == true)
            assertEquals(2, saved?.autoTargetGroupId)
        }
    }

    @Test fun organizerPriorityPreviewAndConfirmationAreSeparate() {
        val preferences = context.getSharedPreferences("tools-ui-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        SmartCollectionStore(preferences).use { store ->
            try {
                store.save(SmartCollection("a", "First", MediaLibraryRule(), true, 2))
                store.save(SmartCollection("b", "Second", MediaLibraryRule(), true, 3))
                var applications = 0
                var appliedTarget: Int? = null
                compose.setContent {
                    val catalog by store.catalog.collectAsState()
                    MaterialTheme { LibraryOrganizerDialog(
                        catalog = catalog,
                        groups = listOf(DownloadGroupEntity("HD", 1, 2), DownloadGroupEntity("Other", 2, 3)),
                        onDismiss = {}, onSave = { withContext(Dispatchers.IO) { store.save(it) } },
                        onMove = { id, offset -> withContext(Dispatchers.IO) { store.move(id, offset) } },
                        onPreview = { listOf(requireNotNull(AutoOrganizer.target(libraryItem(1).video,
                            store.catalog.value.collections, setOf(1, 2, 3), System.currentTimeMillis()))) },
                        onApply = { assignments -> applications++; appliedTarget = assignments.single().targetGroupId; assignments.size },
                    ) }
                }
                compose.onNodeWithText(text(R.string.library_confirm_organize)).assertIsNotEnabled()
                compose.onNodeWithTag("library.auto.up.b").performScrollTo().performClick()
                compose.waitUntil(timeoutMillis = 5_000) { store.catalog.value.collections.firstOrNull()?.id == "b" }
                compose.runOnIdle { assertEquals("b", store.catalog.value.collections.first().id) }
                compose.onNodeWithText(text(R.string.library_preview_organize)).performClick()
                compose.onNodeWithText(context.getString(R.string.library_organize_preview, 1)).performScrollTo().assertIsDisplayed()
                compose.runOnIdle { assertEquals(0, applications) }
                capture("library-organizer.png")
                compose.onNodeWithText(text(R.string.library_confirm_organize)).performClick()
                compose.onNodeWithText(context.getString(R.string.library_organize_done, 1)).assertIsDisplayed()
                compose.runOnIdle { assertEquals(1, applications); assertEquals(3, appliedTarget) }
            } finally { preferences.edit().clear().commit() }
        }
    }

    @Test fun missingRecordNeedsConfirmationAndStaleReportsDisableActions() {
        val missing = InspectedFile(libraryItem(1).video, FileProbe(FileStatus.MISSING))
        val unavailable = InspectedFile(libraryItem(2).video, FileProbe(FileStatus.UNAVAILABLE))
        val report = InspectionReport(listOf(missing.video, unavailable.video), listOf(missing, unavailable), emptyList(), emptyList())
        var state by mutableStateOf(InspectionState(report = report))
        var removedId: Int? = null
        var deleted = false
        compose.setContent { MaterialTheme { LibraryInspectionDialog(
            state = state, onStart = {}, onCancel = {}, onDismiss = {}, onPlay = {},
            onDelete = { deleted = true }, onRemoveMissing = { removedId = it.id; true },
        ) } }
        capture("library-inspection.png")
        compose.onNodeWithText(text(R.string.library_remove_missing)).performScrollTo().performClick()
        compose.runOnIdle { assertNull(removedId) }
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        compose.runOnIdle { assertEquals(1, removedId); assertFalse(deleted); state = state.copy(stale = true) }
        compose.onNodeWithText(text(R.string.library_inspect_stale)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_remove_missing)).assertDoesNotExist()
    }

    @Test fun inspectionShowsProgressAndCanBeCancelled() {
        var cancelled = false
        compose.setContent { MaterialTheme { LibraryInspectionDialog(
            state = InspectionState(running = true, progress = InspectionProgress(InspectionStage.HASHES, 1, 3)),
            onStart = {}, onCancel = { cancelled = true }, onDismiss = {}, onPlay = {}, onDelete = {}, onRemoveMissing = { false },
        ) } }
        compose.onNodeWithText(context.getString(R.string.library_scan_hashes, 1, 3)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_scan_start)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.library_scan_cancel)).performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }
}
