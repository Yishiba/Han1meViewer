package com.yenaly.han1meviewer.logic.backup

import android.content.Context

data class BackupSettings(val enabled: Boolean = false, val days: Int = 1, val keep: Int = 7,
                          val mirrorUri: String? = null, val lastSuccess: Long = 0,
                          val lastFailure: Boolean = false, val mirrorFailed: Boolean = false, val encryptedMirror: Boolean = false) {
    fun validate() { require(days in setOf(1, 7) && keep in setOf(3, 7, 14, 30)) }
}

/** Device-local configuration deliberately stays outside exported default preferences. */
class BackupSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("backup_device_config", Context.MODE_PRIVATE)
    fun read() = BackupSettings(preferences.getBoolean("enabled", false),
        preferences.getInt("days", 1), preferences.getInt("keep", 7), preferences.getString("mirror", null),
        preferences.getLong("success", 0), preferences.getBoolean("failed", false),
        preferences.getBoolean("mirror_failed", false), preferences.getBoolean("encrypted_mirror", false)).also { it.validate() }

    fun save(value: BackupSettings) {
        value.validate()
        check(preferences.edit().putBoolean("enabled", value.enabled).putInt("days", value.days)
            .putInt("keep", value.keep).putString("mirror", value.mirrorUri)
            .putLong("success", value.lastSuccess).putBoolean("failed", value.lastFailure)
            .putBoolean("mirror_failed", value.mirrorFailed).putBoolean("encrypted_mirror", value.encryptedMirror).commit())
    }
}
