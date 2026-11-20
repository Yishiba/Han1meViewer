package com.yenaly.han1meviewer.ui.screen.settings.backup

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.yenaly.han1meviewer.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BackupPasswordUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun defaultEncryptedExportRequiresMatchingLongPasswords() {
        var result: String? = null
        compose.setContent { MaterialTheme { BackupPasswordDialog(true, {}, { result = it }, allowPlain = true) } }
        compose.onNodeWithTag("backup.crypto.submit").assertIsNotEnabled()
        compose.onNodeWithTag("backup.crypto.password").performTextInput("fixture-password-2026")
        compose.onNodeWithTag("backup.crypto.confirmation").performTextInput("different-password")
        compose.onNodeWithTag("backup.crypto.submit").assertIsNotEnabled()
        compose.onNodeWithTag("backup.crypto.confirmation").performTextReplacement("fixture-password-2026")
        compose.onNodeWithTag("backup.crypto.submit").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("fixture-password-2026", result) }
    }
    @Test fun plainExportIsExplicitAndDoesNotPassPassword() {
        var confirmed = false
        var result: String? = "unset"
        compose.setContent { MaterialTheme { BackupPasswordDialog(true, {}, { confirmed = true; result = it }, allowPlain = true) } }
        compose.onNodeWithTag("backup.crypto.plain").performClick()
        compose.onNodeWithTag("backup.crypto.password").assertDoesNotExist()
        compose.onNodeWithTag("backup.crypto.submit").performClick()
        compose.runOnIdle { assertTrue(confirmed); assertNull(result) }
    }
    @Test fun unlockAllowsRetryAndBlocksChangesDuringWork() {
        var busy by mutableStateOf(false)
        var attempts = 0
        compose.setContent { MaterialTheme { BackupPasswordDialog(false, {}, { attempts++ }, busy = busy, error = R.string.backup_crypto_wrong_password) } }
        compose.onNodeWithTag("backup.crypto.password").performTextInput("retry-password")
        compose.onNodeWithTag("backup.crypto.confirmation").assertDoesNotExist()
        compose.onNodeWithTag("backup.crypto.submit").performClick()
        compose.runOnIdle { assertEquals(1, attempts); busy = true }
        compose.onNodeWithTag("backup.crypto.submit").assertIsNotEnabled()
        compose.onNodeWithTag("backup.crypto.password").assertIsNotEnabled()
        compose.runOnIdle { busy = false }
        compose.onNodeWithTag("backup.crypto.password").performTextReplacement("correct-password")
        compose.onNodeWithTag("backup.crypto.submit").performClick()
        compose.runOnIdle { assertEquals(2, attempts) }
    }
    @Test fun historyExportOpensUsablePasswordDialogAboveHistoryWithoutPayment() {
        val version = com.yenaly.han1meviewer.logic.backup.BackupVersion("sample", 1794888000000,
            com.yenaly.han1meviewer.logic.backup.BackupKind.MANUAL, 4096, "digest", 18, 40, 3)
        compose.setContent { MaterialTheme {
            val transfers = rememberBackupTransfers({})
            BackupHistoryDialog(com.yenaly.han1meviewer.logic.backup.BackupUiState(versions = listOf(version), loaded = true),
                false, null, {}, {}, {}, {}, {}, transfers.exportVersion, {})
        } }
        compose.onNodeWithTag("backup.list").performScrollToNode(hasTestTag("backup.version.sample"))
        compose.onNodeWithTag("backup.export.sample").performScrollTo().performClick()
        compose.onNodeWithTag("backup.crypto.password").assertIsDisplayed().performClick().performTextInput("fixture-only-password")
        compose.onNodeWithTag("backup.crypto.password").assertIsFocused()
        compose.onNodeWithTag("backup.crypto.submit").assertIsNotEnabled()
    }

}
