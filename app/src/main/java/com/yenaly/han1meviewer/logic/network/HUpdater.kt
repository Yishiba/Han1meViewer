package com.yenaly.han1meviewer.logic.network

import android.util.Log
import com.yenaly.han1meviewer.Preferences
import com.yenaly.han1meviewer.logic.model.github.Latest
import com.yenaly.han1meviewer.util.checkNeedUpdate
import com.yenaly.han1meviewer.util.copyTo
import retrofit2.HttpException
import java.io.File

/**
 * @project Han1meViewer
 * @author Yenaly Liew
 * @time 2024/03/21 021 08:28
 */
object HUpdater {

    const val TAG = "HUpdater"

    /**
     * Check for update
     *
     * @param forceCheck force check
     */
    suspend fun checkForUpdate(forceCheck: Boolean = false): Latest? {
        if (forceCheck || Preferences.isUpdateDialogVisible) {
            val ver = try {
                HanimeNetwork.githubService.getLatestVersion()
            } catch (error: HttpException) {
                // A new fork has no published release yet.
                if (error.code() == 404) return null else throw error
            }
            if (checkNeedUpdate(ver.tagName)) {
                val asset = ver.assets.singleOrNull {
                    it.name.startsWith("Han1meShelf-v") && it.name.endsWith(".apk") &&
                        it.state == "uploaded"
                } ?: return null
                val digest = asset.digest?.removePrefix("sha256:") ?: return null
                if (!digest.matches(Regex("[a-fA-F0-9]{64}"))) return null
                return Latest(
                    ver.tagName, ver.body, asset.browserDownloadURL, asset.nodeID,
                    digest,
                )
            }
        }
        return null
    }

    /**
     * Inject update to file
     *
     * @param url update url
     */
    suspend fun File.injectUpdate(url: String, progress: (suspend (Int, Long, Long) -> Unit)? = null) {
        val res = HanimeNetwork.githubService.request(url)
        if (!res.isSuccessful) throw IllegalStateException("Release download failed: HTTP ${res.code()}")
        val body = res.body() ?: throw IllegalStateException("Release download is empty")
        Log.d(TAG, "Downloading release ($url)")
        this.outputStream().use { output ->
            body.use {
                it.byteStream().copyTo(output, it.contentLength(), progress = progress)
            }
        }
    }
}
