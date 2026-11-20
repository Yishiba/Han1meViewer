package com.yenaly.han1meviewer

import android.content.ComponentName
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.color.DynamicColors
import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import com.google.firebase.crashlytics.setCustomKeys
import com.google.firebase.database.database
import com.yenaly.han1meviewer.logic.network.HProxySelector
import com.yenaly.han1meviewer.ui.viewmodel.AppViewModel
import com.yenaly.han1meviewer.util.AnimeShaders
import com.yenaly.han1meviewer.util.ThemeUtils
import com.yenaly.yenaly_libs.base.YenalyApplication
import com.yenaly.yenaly_libs.utils.LanguageHelper
import `is`.xyz.mpv.MPVLib
import java.net.ProxySelector
import com.yenaly.han1meviewer.logic.backup.BackupService
import kotlinx.coroutines.*

/**
 * @project Hanime1
 * @author Yenaly Liew
 * @time 2022/06/08 008 17:32
 */
class HanimeApplication : YenalyApplication() {

    companion object {
        const val TAG = "HanimeApplication"
    }

    /**
     * 已经在 [HInitializer] 中处理了
     */
    override val isDefaultCrashHandlerEnabled: Boolean = false

    override fun onCreate() {
        super.onCreate()
        val backups = BackupService.get(this)
        // Only interrupted restores need blocking recovery, before any ordinary database writers start.
        try { runBlocking { backups.recoverOnStartup() } }
        catch (_: Exception) { Log.e(TAG, "Backup recovery pending; library access is blocked") }
        ThemeUtils.applyDarkModeFromPreferences(this)
        if (Preferences.useDynamicColor){
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
        ProxySelector.setDefault(HProxySelector())
        HProxySelector.rebuildNetwork()
        initFirebase()
        initNotificationChannel()
        MPVLib.create(applicationContext)
        MPVLib.init()

        if (AnimeShaders.copyShaderAssets(applicationContext) <= 0) {
            Log.w(TAG, "Shader 复制失败")
        }
        if (AnimeShaders.copyCertAssets(applicationContext) <= 0) {
            Log.w(TAG, "cert 复制失败")
        }
        val selected = Preferences.fakeLauncherIcon
        switchLauncher(selected)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { backups.initialize() }
            catch (_: Exception) { Log.w(TAG, "Backup initialization failed") }
            try { com.yenaly.han1meviewer.logic.remote.AutomaticSyncScheduler(this@HanimeApplication).initialize() }
            catch (_: Exception) { Log.w(TAG, "Automatic sync initialization failed") }
        }
    }

    private fun initFirebase() {
        // 用于处理 Firebase Analytics 初始化
        Firebase.analytics.setAnalyticsCollectionEnabled(Preferences.isAnalyticsEnabled)
        // 用于处理 Firebase Crashlytics 初始化
        Firebase.crashlytics.apply {
            isCrashlyticsCollectionEnabled = !BuildConfig.DEBUG
            setCustomKeys {
                key(
                    FirebaseConstants.APP_LANGUAGE,
                    LanguageHelper.preferredLanguage.toLanguageTag()
                )
                key(
                    FirebaseConstants.VERSION_SOURCE,
                    BuildConfig.VERSION_SOURCE
                )
            }
        }
        AppViewModel.getLatestVersion(delayMillis = 200)
        Firebase.database.setPersistenceEnabled(true)
    }

    private fun initNotificationChannel() {
        val nm = NotificationManagerCompat.from(this)

        val hanimeDownloadChannel = NotificationChannelCompat.Builder(
            DOWNLOAD_NOTIFICATION_CHANNEL,
            NotificationManagerCompat.IMPORTANCE_HIGH
        ).setName("Hanime Download").build()
        nm.createNotificationChannel(hanimeDownloadChannel)

        val appUpdateChannel = NotificationChannelCompat.Builder(
            UPDATE_NOTIFICATION_CHANNEL,
            NotificationManagerCompat.IMPORTANCE_HIGH
        ).setName("App Update").build()
        nm.createNotificationChannel(appUpdateChannel)
    }
    fun switchLauncher(alias: String) {
        val pm = packageManager

        val allAliases = listOf(
            "com.yenaly.han1meviewer.LauncherAliasDefault",
            "com.yenaly.han1meviewer.LauncherFakeCalc",
            "com.yenaly.han1meviewer.LauncherFakeCornhub",
            "com.yenaly.han1meviewer.LauncherFakeXxt"
        )

        allAliases.forEach { a ->
            val state = if (a == alias)
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED

            pm.setComponentEnabledSetting(
                ComponentName(this, a),
                state,
                PackageManager.DONT_KILL_APP
            )
        }
    }
}
