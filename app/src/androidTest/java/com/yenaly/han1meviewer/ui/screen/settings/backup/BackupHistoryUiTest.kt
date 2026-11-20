package com.yenaly.han1meviewer.ui.screen.settings.backup

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.yenaly.han1meviewer.R
import com.yenaly.han1meviewer.logic.backup.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class BackupHistoryUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val version = BackupVersion("sample", 1794888000000, BackupKind.MANUAL, 4096, "digest", 18, 40, 3)

    @Test fun configurationChangesAndCreatingBackupDoNotRequirePayment() {
        var state by mutableStateOf(BackupUiState(loaded = true))
        var creates = 0
        compose.setContent { MaterialTheme { BackupHistoryDialog(state, false, null, {},
            onConfigure = { state = state.copy(settings = it) }, onCreate = { creates++ },
            onChooseDirectory = {}, onRestore = {}, onExport = {}, onDelete = {}) } }
        compose.onNodeWithTag("backup.enabled").performClick()
        compose.onNodeWithTag("backup.days.7").performScrollTo().performClick()
        compose.onNodeWithTag("backup.keep.14").performScrollTo().performClick()
        compose.onNodeWithTag("backup.create").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(state.settings.enabled); assertEquals(7, state.settings.days); assertEquals(14, state.settings.keep); assertEquals(1, creates) }
    }

    @Test fun restoringRequiresExplicitConfirmationAndDisplaysVersionSummary() {
        var restored: BackupVersion? = null
        compose.setContent { MaterialTheme { BackupHistoryDialog(BackupUiState(versions = listOf(version), loaded = true),
            false, null, {}, {}, {}, {}, onRestore = { restored = it }, onExport = {}, onDelete = {}) } }
        compose.onNodeWithTag("backup.list").performScrollToNode(hasTestTag("backup.version.sample"))
        compose.onNodeWithTag("backup.restore.sample").performScrollTo().performClick()
        compose.runOnIdle { assertNull(restored) }
        compose.onNodeWithTag("backup.confirm.restore").performClick()
        compose.runOnIdle { assertEquals(version, restored) }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5000)
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "backup-history.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }

    @Test fun exportSelectsVersionAndDeleteRequiresConfirmationWhileSafetyVersionIsProtected() {
        var exported: BackupVersion? = null
        var deleted: BackupVersion? = null
        val safety = version.copy(id = "safety", kind = BackupKind.BEFORE_RESTORE)
        compose.setContent { MaterialTheme { BackupHistoryDialog(BackupUiState(versions = listOf(version, safety), loaded = true),
            false, null, {}, {}, {}, {}, {}, onExport = { exported = it }, onDelete = { deleted = it }) } }
        compose.onNodeWithTag("backup.list").performScrollToNode(hasTestTag("backup.version.sample"))
        compose.onNodeWithTag("backup.export.sample").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(version, exported) }
        compose.onNodeWithTag("backup.delete.sample").performScrollTo().performClick()
        compose.runOnIdle { assertNull(deleted) }
        compose.onNodeWithTag("backup.confirm.delete").performClick()
        compose.runOnIdle { assertEquals(version, deleted) }
        compose.onNodeWithTag("backup.list").performScrollToNode(hasTestTag("backup.version.safety"))
        compose.onNodeWithTag("backup.restore.safety").performScrollTo().assertExists()
        compose.onNodeWithTag("backup.delete.safety").assertDoesNotExist()
    }

    @Test fun busyStateBlocksMutationsAndFailureIsVisible() {
        compose.setContent { MaterialTheme { BackupHistoryDialog(BackupUiState(versions = listOf(version), loaded = true),
            true, R.string.backup_operation_failed, {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("backup.enabled").assertIsNotEnabled()
        compose.onNodeWithTag("backup.create").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("backup.list").performScrollToNode(hasTestTag("backup.version.sample"))
        compose.onNodeWithTag("backup.restore.sample").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("backup.message").assertIsDisplayed()
    }
}
