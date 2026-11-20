package com.yenaly.han1meviewer.logic

import android.content.Context
import android.net.Uri
import com.yenaly.han1meviewer.logic.backup.BackupService

/** Programmatic export/import shares the version vault and protected restore path. */
object BackupManager {
    suspend fun exportTo(context: Context, uri: Uri, password: String? = null) = BackupService.get(context).exportCurrent(uri, password)
    suspend fun importFrom(context: Context, uri: Uri, password: String? = null) = BackupService.get(context).import(uri, password)
}
