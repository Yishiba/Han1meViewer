package com.yenaly.han1meviewer.logic.network

import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.ui.navigation.settings.SettingsPreferenceKeys
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DohDefaultsDeviceTest {
    private val prefs get() = Preferences.preferenceSp
    private val keys = listOf(
        SettingsPreferenceKeys.USE_DOH,
        SettingsPreferenceKeys.DOH_PRESET,
        SettingsPreferenceKeys.DOH_CUSTOM_URL,
        SettingsPreferenceKeys.DOH_BOOTSTRAP_IPS,
        SettingsPreferenceKeys.USE_BUILT_IN_HOSTS,
    )
    private var saved: Map<String, Any?> = emptyMap()

    @Before fun resetNetworkPreferences() {
        saved = prefs.all.filterKeys { it in keys }
        assertTrue(prefs.edit().apply { keys.forEach { remove(it) } }.commit())
    }

    @After fun restoreNetworkPreferences() {
        val editor = prefs.edit()
        keys.forEach { editor.remove(it) }
        saved.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
            }
        }
        assertTrue(editor.commit())
    }

    @Test fun freshInstallUsesGoogleDohWithoutSystemBootstrap() {
        assertTrue("Fresh installs must avoid system DNS by default", Preferences.useDoH)
        assertEquals("google", Preferences.dohPreset)
        assertEquals("https://dns.google/dns-query", DohConfig.resolveUrl())
        assertEquals(listOf("8.8.8.8", "8.8.4.4"), DohConfig.bootstrapIps())
        assertFalse(Preferences.useBuiltInHosts)
        assertFalse("Defaults must not overwrite stored preferences", prefs.contains(SettingsPreferenceKeys.USE_DOH))
    }

    @Test fun explicitlyDisabledDohStaysDisabled() {
        prefs.edit().putBoolean(SettingsPreferenceKeys.USE_DOH, false).commit()
        assertFalse(Preferences.useDoH)
        assertNull(DohConfig.resolveUrl())
    }

    @Test fun existingBuiltInHostsDoNotEnableDohImplicitly() {
        prefs.edit().putBoolean(SettingsPreferenceKeys.USE_BUILT_IN_HOSTS, true).commit()
        assertFalse(Preferences.useDoH)
        assertNull(DohConfig.resolveUrl())
    }

    @Test fun savedPresetAndBootstrapArePreserved() {
        prefs.edit()
            .putBoolean(SettingsPreferenceKeys.USE_DOH, true)
            .putString(SettingsPreferenceKeys.DOH_PRESET, "dnspod")
            .commit()
        assertEquals("https://doh.pub/dns-query", DohConfig.resolveUrl())
        assertEquals(listOf("1.12.12.12", "120.53.53.53"), DohConfig.bootstrapIps())
    }

    @Test fun customDohAndBootstrapArePreserved() {
        prefs.edit()
            .putBoolean(SettingsPreferenceKeys.USE_DOH, true)
            .putString(SettingsPreferenceKeys.DOH_PRESET, "custom")
            .putString(SettingsPreferenceKeys.DOH_CUSTOM_URL, "https://resolver.example/dns-query")
            .putString(SettingsPreferenceKeys.DOH_BOOTSTRAP_IPS, "192.0.2.1, 192.0.2.2")
            .commit()
        assertEquals("https://resolver.example/dns-query", DohConfig.resolveUrl())
        assertEquals(listOf("192.0.2.1", "192.0.2.2"), DohConfig.bootstrapIps())
    }
}
