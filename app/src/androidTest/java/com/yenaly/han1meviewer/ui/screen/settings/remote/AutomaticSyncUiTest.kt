package com.yenaly.han1meviewer.ui.screen.settings.remote

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.yenaly.han1meviewer.logic.remote.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AutomaticSyncUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun autoSyncIsOptInAndSupportsFrequencyAndNetworkWithoutPayment() {
        var config by mutableStateOf(AutomaticSyncSettings())
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            AutomaticSyncControls(config, true) { enabled, minutes, unmetered -> config = config.copy(enabled = enabled, minutes = minutes, unmetered = unmetered) }
        } } }
        compose.onNodeWithTag("remote.auto.enabled").assertIsOff().performClick()
        compose.onNodeWithTag("remote.auto.minutes.15").performScrollTo().performClick()
        compose.onNodeWithTag("remote.auto.unmetered").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(config.enabled); assertEquals(15, config.minutes); assertFalse(config.unmetered) }
        compose.onNodeWithTag("remote.auto.enabled").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(config.enabled) }
    }
    @Test fun blockedStatusIsVisibleAndBusyConfigurationPreventsChanges() {
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            AutomaticSyncControls(AutomaticSyncSettings(enabled = true, status = AutomaticSyncStatus.CONFLICT), false) { _, _, _ -> fail() }
        } } }
        compose.onNodeWithTag("remote.auto.enabled").assertIsNotEnabled()
        compose.onNodeWithTag("remote.auto.minutes.60").assertIsNotEnabled()
        compose.onNodeWithTag("remote.auto.status").performScrollTo().assertIsDisplayed()
    }
}
