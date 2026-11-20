package com.yenaly.han1meviewer.logic.remote

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

enum class AutomaticSyncStatus { IDLE, SUCCESS, CONFLICT, AUTH, INVALID, BUSY, RETRY, LOCAL_CHANGED }
data class AutomaticSyncSettings(
    val enabled: Boolean = false, val minutes: Int = 60, val unmetered: Boolean = true,
    val generation: String = UUID.randomUUID().toString(), val status: AutomaticSyncStatus = AutomaticSyncStatus.IDLE,
    val lastAttempt: Long = 0, val lastSuccess: Long = 0,
) {
    val blocked get() = status in setOf(AutomaticSyncStatus.CONFLICT, AutomaticSyncStatus.AUTH, AutomaticSyncStatus.INVALID)
    fun validate() { require(minutes in setOf(15, 60, 360) && generation.length in 1..100) }
}

/** Device-local controls, excluded from ordinary backup and system migration. Never stores queries or credentials. */
class AutomaticSyncSettingsStore(context: Context,
    private val prefs: SharedPreferences = context.getSharedPreferences("automatic_sync_config", Context.MODE_PRIVATE)) {
    companion object { private val lock = Any() }
    fun read(): AutomaticSyncSettings = synchronized(lock) {
        AutomaticSyncSettings(prefs.getBoolean("enabled", false), prefs.getInt("minutes", 60), prefs.getBoolean("unmetered", true),
            prefs.getString("generation", "unconfigured")!!,
            AutomaticSyncStatus.entries.firstOrNull { it.name == prefs.getString("status", null) } ?: AutomaticSyncStatus.IDLE,
            prefs.getLong("attempt", 0), prefs.getLong("success", 0)).also { it.validate() }
    }
    fun update(action: (AutomaticSyncSettings) -> AutomaticSyncSettings): AutomaticSyncSettings = synchronized(lock) {
        action(read()).also { value ->
            value.validate()
            check(prefs.edit().putBoolean("enabled", value.enabled).putInt("minutes", value.minutes).putBoolean("unmetered", value.unmetered)
                .putString("generation", value.generation).putString("status", value.status.name)
                .putLong("attempt", value.lastAttempt).putLong("success", value.lastSuccess).commit())
        }
    }
    fun observe() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
