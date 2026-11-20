package com.yenaly.han1meviewer.ui.screen.settings.remote

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.test.platform.app.InstrumentationRegistry
import android.content.ContextWrapper
import java.io.File
import java.util.UUID
import com.yenaly.han1meviewer.logic.remote.*
import com.yenaly.han1meviewer.R
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.yenaly.han1meviewer.logic.remote.DavEntry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RemoteLibraryUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun folderAndVideoActionsAreAvailableButUnsupportedFilesAreDisabled() {
        var opened: DavEntry? = null
        val folder = DavEntry("https://example.com/dav/folder/", "Folder", true, -1)
        val video = DavEntry("https://example.com/dav/video.mp4", "Video.mp4", false, 1024)
        val other = DavEntry("https://example.com/dav/file.txt", "Notes.txt", false, 20)
        compose.setContent { MaterialTheme { Column { RemoteBrowserRows(listOf(folder, video, other), false) { opened = it } } } }
        compose.onNodeWithTag("remote.open.Notes.txt").assertIsNotEnabled()
        compose.onNodeWithTag("remote.open.Folder").performClick()
        compose.runOnIdle { assertEquals(folder, opened) }
        compose.onNodeWithTag("remote.open.Video.mp4").performClick()
        compose.runOnIdle { assertEquals(video, opened) }
    }
    @Test fun syncRequiresExplicitConfirmationAndBusyStateDisablesIt() {
        var confirmed = false
        compose.setContent { MaterialTheme { Column { RemoteSyncPreview(3, 1, false) { confirmed = true } } } }
        compose.runOnIdle { assertFalse(confirmed) }
        compose.onNodeWithTag("remote.sync.confirm").performClick()
        compose.runOnIdle { assertTrue(confirmed) }
    }
    @Test fun busyBrowserPreventsOpeningFiles() {
        compose.setContent { MaterialTheme { Column { RemoteBrowserRows(listOf(DavEntry("https://example.com/dav/a.mp4", "a.mp4", false, 100)), true) { fail() } } } }
        compose.onNodeWithTag("remote.open.a.mp4").assertIsNotEnabled()
    }
    @Test fun actualPlayerDecodesAuthenticatedHttpsMediaAndSavesPositionOnClose() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "dav-player-${UUID.randomUUID()}").apply { mkdirs() }
        val store = RemoteStore(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder })
        DavTlsFixture().use { server ->
            val profile = DavProfile(server.root, "user", "password")
            store.saveProfile(profile)
            val url = server.root + "playback.mp4"
            var showing by mutableStateOf(true)
            try {
                compose.setContent { MaterialTheme {
                    if (showing) RemotePlayerDialog(DavEntry(url, "Sample video", false, -1),
                        WebDavClient(profile.root, profile.username, profile.password, server.client), profile, store, 1000) { showing = false }
                } }
                compose.waitUntil(15000) { compose.onAllNodesWithText(" / 3s", substring = true).fetchSemanticsNodes().isNotEmpty() }
                compose.runOnIdle { showing = false }
                compose.waitUntil(5000) { store.read().progress[url] != null }
                assertTrue(store.read().progress.getValue(url).position >= 1000)
            } finally {
                compose.runOnIdle { showing = false }
                folder.deleteRecursively()
            }
        }
    }

    @Test fun fullConnectionBrowserAndSyncPreviewDoNotPublishUntilConfirmed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "dav-browser-${UUID.randomUUID()}").apply { mkdirs() }
        val store = RemoteStore(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder })
        DavTlsFixture().use { server ->
            val profile = DavProfile(server.root, "user", "password", "shared-test-passphrase")
            store.saveProfile(profile)
            var showing by mutableStateOf(true)
            try {
                compose.setContent { MaterialTheme {
                    if (showing) RemoteLibraryDialog({}, {}, store, { p -> WebDavClient(p.root, p.username, p.password, server.client) })
                } }
                compose.waitUntil(10000) { compose.onAllNodes(hasTestTag("remote.auto.enabled") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("remote.list").performScrollToNode(hasTestTag("remote.open.sample.mp4"))
                compose.onNodeWithTag("remote.open.sample.mp4").assertIsDisplayed()
                compose.onNodeWithText(context.getString(R.string.remote_sync_preview)).performScrollTo().performClick()
                compose.waitUntil(10000) { compose.onAllNodesWithTag("remote.sync.confirm").fetchSemanticsNodes().isNotEmpty() }
                compose.runOnIdle { assertNull(server.document) }
                compose.onNodeWithTag("remote.sync.confirm").performScrollTo().assertIsEnabled()
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5000)
                val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                File(context.getExternalFilesDir(null), "remote-library.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            } finally {
                compose.runOnIdle { showing = false }
                folder.deleteRecursively()
            }
        }
    }

    @Test fun autoSyncCanBeDisabledWhenSavedConnectionIsDenied() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "dav-offline-${UUID.randomUUID()}").apply { mkdirs() }
        val store = RemoteStore(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder })
        val settings = AutomaticSyncSettingsStore(context)
        val original = settings.read()
        var showing by mutableStateOf(true)
        DavTlsFixture().use { server ->
            try {
                store.saveProfile(DavProfile(server.root, "user", "wrong-password", "fixture-shared-passphrase"))
                settings.update { it.copy(enabled = true, generation = "offline-fixture", status = AutomaticSyncStatus.AUTH) }
                compose.setContent { MaterialTheme {
                    if (showing) RemoteLibraryDialog({}, {}, store, { p -> WebDavClient(p.root, p.username, p.password, server.client) })
                } }
                compose.waitUntil(10000) { compose.onAllNodes(hasTestTag("remote.auto.enabled") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("remote.auto.enabled").performScrollTo().assertIsOn().performClick()
                compose.waitUntil(5000) { !settings.read().enabled }
                assertNull(server.document)
            } finally {
                compose.runOnIdle { showing = false }
                settings.update { original }
                kotlinx.coroutines.runBlocking { AutomaticSyncScheduler(context).initialize() }
                folder.deleteRecursively()
            }
        }
    }

    @Test fun realSettingsEnableAndRescheduleBackgroundSyncOnEmulator() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "dav-auto-ui-${UUID.randomUUID()}").apply { mkdirs() }
        val store = RemoteStore(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder })
        val settings = AutomaticSyncSettingsStore(context)
        val original = settings.read()
        var showing by mutableStateOf(true)
        DavTlsFixture().use { server ->
            try {
                settings.update { it.copy(enabled = false, status = AutomaticSyncStatus.IDLE) }
                store.saveProfile(DavProfile(server.root, "user", "password", "fixture-shared-passphrase"))
                compose.setContent { MaterialTheme {
                    if (showing) RemoteLibraryDialog({}, {}, store, { p -> WebDavClient(p.root, p.username, p.password, server.client) })
                } }
                compose.waitUntil(10000) { compose.onAllNodes(hasTestTag("remote.auto.enabled") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("remote.auto.enabled").performScrollTo().assertIsOff().performClick()
                compose.waitUntil(5000) { settings.read().enabled }
                compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("remote.auto.minutes.15") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("remote.auto.minutes.15").performScrollTo().performClick()
                compose.waitUntil(5000) { settings.read().minutes == 15 }
                compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("remote.auto.enabled") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("remote.auto.enabled").assertIsOn()
                val tasks = androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork(AutomaticSyncScheduler.WORK_NAME).get()
                assertEquals(1, tasks.count { !it.state.isFinished })
                assertNull(server.document)
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5000)
                val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                File(context.getExternalFilesDir(null), "automatic-sync.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            } finally {
                compose.runOnIdle { showing = false }
                kotlinx.coroutines.runBlocking { AutomaticSyncScheduler(context, settings, store).configure(false, 60, true) }
                settings.update { original }
                kotlinx.coroutines.runBlocking { AutomaticSyncScheduler(context).initialize() }
                folder.deleteRecursively()
            }
        }
    }

}
