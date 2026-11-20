package com.yenaly.han1meviewer.logic.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import com.yenaly.han1meviewer.BuildConfig
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.dao.*

class AndroidBackupBackend(
    context: Context,
    private val preferences: SharedPreferences = Preferences.preferenceSp,
    private val downloads: DownloadDatabase = DownloadDatabase.instance,
    private val history: HistoryDatabase = HistoryDatabase.instance,
    private val miscellany: MiscellanyDatabase = MiscellanyDatabase.instance,
    private val checkIn: CheckInRecordDatabase = CheckInRecordDatabase.getDatabase(context),
    private val searchTools: com.yenaly.han1meviewer.logic.translation.SearchToolsStore = com.yenaly.han1meviewer.logic.translation.SearchToolsStore(context),
) : BackupBackend {
    override suspend fun capture(): BackupPayload {
        val settings = preferences.all.filterKeys(BackupCodec::restorableKey).mapNotNull { (key, value) ->
            val typed = when (value) {
                is Boolean -> PreferenceValue.BooleanValue(value)
                is Float -> PreferenceValue.FloatValue(value)
                is Int -> PreferenceValue.IntValue(value)
                is Long -> PreferenceValue.LongValue(value)
                is String -> PreferenceValue.StringValue(value)
                is Set<*> -> PreferenceValue.StringSetValue(value.filterIsInstance<String>().toSet())
                else -> null
            }
            typed?.let { key to it }
        }.toMap()
        val search = searchTools.snapshot()
        var snapshot = BackupPayload(version = 3, appVersionCode = BuildConfig.VERSION_CODE,
            appVersionName = BuildConfig.VERSION_NAME, exportedAt = System.currentTimeMillis(), settings = settings, searchTools = search.first, searchToolsRaw = search.second)
        miscellany.withTransaction { snapshot = snapshot.copy(hKeyframes = miscellany.hKeyframeDao.getAll()) }
        checkIn.withTransaction { snapshot = snapshot.copy(checkInRecords = checkIn.checkInDao().getAllRecords(),
            sideDishes = checkIn.sideDishDao().getAll()) }
        history.withTransaction { snapshot = snapshot.copy(watchHistories = history.watchHistory.getAll()) }
        downloads.withTransaction {
            snapshot = snapshot.copy(downloadGroups = downloads.downloadGroupDao.getAllGroupsOnce(),
                downloads = downloads.hanimeDownloadDao.getAll(),
                downloadCategories = downloads.downloadCategoryDao.getAllCategoriesOnce(),
                downloadCategoryCrossRefs = downloads.downloadCategoryDao.getAllCrossRefs())
        }
        return snapshot.safeForRestore()
    }

    /** Sync adds missing group definitions and updates only watch/settings metadata. Existing download
     * rows and parent groups are never replaced, preserving failed/paused states and manual grouping. */
    suspend fun applySynchronized(payload: BackupPayload) {
        BackupCodec.validate(payload)
        downloads.withTransaction {
            val existing = downloads.downloadGroupDao.getAllGroupsOnce().associateBy { it.id }
            payload.downloadGroups.orEmpty().forEach { group ->
                val current = existing[group.id]
                if (current == null) downloads.downloadGroupDao.insert(group)
                else require(current == group) { "Local group changed during sync" }
            }
        }
        apply(payload.copy(downloadGroups = null, downloads = null, downloadCategories = null,
            downloadCategoryCrossRefs = null, hKeyframes = null, checkInRecords = null, sideDishes = null))
    }

    override suspend fun apply(payload: BackupPayload) {
        val data = payload.safeForRestore().also(BackupCodec::validate)
        data.hKeyframes?.let { records -> miscellany.withTransaction {
            miscellany.hKeyframeDao.deleteAll(); miscellany.hKeyframeDao.insertAll(records)
        } }
        checkIn.withTransaction {
            data.checkInRecords?.let { checkIn.checkInDao().deleteAll(); checkIn.checkInDao().insertAll(it) }
            data.sideDishes?.let { checkIn.sideDishDao().deleteAll(); checkIn.sideDishDao().insertAll(it) }
        }
        data.watchHistories?.let { records -> history.withTransaction {
            history.watchHistory.deleteAll(); history.watchHistory.insertAll(records)
        } }
        if (data.downloadGroups != null || data.downloads != null || data.downloadCategories != null ||
            data.downloadCategoryCrossRefs != null) downloads.withTransaction {
            downloads.downloadCategoryDao.deleteAllCrossRefs()
            downloads.hanimeDownloadDao.deleteAll()
            downloads.downloadCategoryDao.deleteAllCategories()
            downloads.downloadGroupDao.deleteAll()
            downloads.downloadGroupDao.insertAll(data.downloadGroups.orEmpty())
            downloads.downloadGroupDao.insertDefaultGroup()
            downloads.downloadCategoryDao.insertAllCategories(data.downloadCategories.orEmpty())
            downloads.hanimeDownloadDao.insertAll(data.downloads.orEmpty())
            downloads.downloadCategoryDao.insertAllCrossRefs(data.downloadCategoryCrossRefs.orEmpty())
        }
        data.settings?.let { settings ->
            val editor = preferences.edit()
            // Full section replacement, while keeping this device's current credentials and consent.
            preferences.all.keys.filter(BackupCodec::restorableKey).forEach(editor::remove)
            settings.forEach { (key, value) -> when (value) {
                is PreferenceValue.BooleanValue -> editor.putBoolean(key, value.value)
                is PreferenceValue.FloatValue -> editor.putFloat(key, value.value)
                is PreferenceValue.IntValue -> editor.putInt(key, value.value)
                is PreferenceValue.LongValue -> editor.putLong(key, value.value)
                is PreferenceValue.StringValue -> editor.putString(key, value.value)
                is PreferenceValue.StringSetValue -> editor.putStringSet(key, value.value)
            } }
            check(editor.commit()) { "Unable to restore preferences" }
        }
        if (data.searchToolsRaw != null) searchTools.replaceRaw(data.searchToolsRaw)
        else data.searchTools?.let(searchTools::replace)
    }
}
