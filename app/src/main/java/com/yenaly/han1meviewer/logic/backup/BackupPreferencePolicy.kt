package com.yenaly.han1meviewer.logic.backup

import com.yenaly.han1meviewer.ui.navigation.settings.SettingsPreferenceKeys as K

/** Reject incompatible known settings before a restore can leave startup getters throwing. */
internal object BackupPreferencePolicy {
    private val booleans = setOf(K.SHOW_PLAYED_INDICATOR, K.USE_CI_UPDATE_CHANNEL, K.USE_ANALYTICS,
        K.USE_DYNAMIC_COLOR, K.ALLOW_RESUME_PLAYBACK, K.SEARCH_ARTIST_IGNORE_VIDEO_TYPE,
        K.DISABLE_MOBILE_DATA_WARNING, K.COLLAPSE_DOWNLOADED_GROUP, K.DISABLE_PREDICTIVE_BACK,
        K.TABLET_MODE, K.SHOW_BOTTOM_PROGRESS, K.USE_CUSTOM_MIRROR_SITE, K.APPEND_CUSTOM_MIRROR_PATH,
        K.USE_BUILT_IN_HOSTS, K.USE_DOH, K.USE_PRIVATE_STORAGE, K.SHOW_COMMENT_WHEN_COUNTDOWN,
        K.H_KEYFRAMES_ENABLE, K.SHARED_H_KEYFRAMES_ENABLE, K.SHARED_H_KEYFRAMES_USE_FIRST,
        K.ENABLE_GPU_NEXT_RENDERER, K.MPV_INTERPOLATION, K.MPV_DEBAND, K.MPV_FRAMEDROP, K.MPV_TLS_VERIFY,
        K.AUTO_TRANSLATE_ENABLED, K.AUTO_TRANSLATE_MOBILE_MODELS,
        "allow_pip_mode", "disable_comments", "use_lock_screen")
    private val integers = setOf(K.UPDATE_POPUP_INTERVAL_DAYS, K.SEARCH_GRID_COLUMNS_COMPACT,
        K.SEARCH_GRID_COLUMNS_MEDIUM, K.SEARCH_GRID_COLUMNS_EXPANDED, K.SEARCH_GRID_COLUMNS_LARGE,
        K.SLIDE_SENSITIVITY, K.DOH_TIMEOUT_SECONDS, K.PROXY_TYPE, K.PROXY_PORT, K.DOWNLOAD_COUNT_LIMIT,
        K.DOWNLOAD_SPEED_LIMIT, K.WHEN_COUNTDOWN_REMIND, K.MPV_CACHE_SECS, K.MPV_NETWORK_TIMEOUT)
    private val strings = setOf(K.VIDEO_LANGUAGE, K.DEFAULT_VIDEO_QUALITY, K.FAKE_LAUNCHER_ICON,
        K.USE_DARK_MODE, K.THEME_COLOR, K.HORIZONTAL_CARD_COUNT_NARROW, K.HORIZONTAL_CARD_COUNT_COMPACT,
        K.HORIZONTAL_CARD_COUNT_MEDIUM, K.HORIZONTAL_CARD_COUNT_EXPANDED, K.HOME_CATEGORY_ORDER,
        K.HOME_CATEGORY_HIDDEN, K.SWITCH_PLAYER_KERNEL, K.PLAYER_SPEED, K.LONG_PRESS_SPEED_TIMES,
        K.DOMAIN_NAME, K.SELECTED_BASE_URL, K.CUSTOM_MIRROR_SITE, K.CUSTOM_HOSTS_DATA, K.DOH_PRESET,
        K.DOH_CUSTOM_URL, K.DOH_BOOTSTRAP_IPS, K.PROXY_IP, K.MPV_PROFILE, K.MPV_HWDEC, K.CUSTOM_PARAMS,
        K.AUTO_TRANSLATE_TARGET,
        "app_language", "saf_download_path", "media_library_smart_collections_v1", "update_node_id")

    fun validate(settings: Map<String, PreferenceValue>?) {
        settings?.filterKeys(BackupCodec::restorableKey)?.forEach { (key, value) ->
            val correct = when (key) {
                in booleans -> value is PreferenceValue.BooleanValue
                in integers -> value is PreferenceValue.IntValue
                in strings -> value is PreferenceValue.StringValue
                K.LAST_UPDATE_POPUP_TIME -> value is PreferenceValue.LongValue
                else -> true // Retain typed, unknown settings for compatibility with optional features.
            }
            require(correct) { "Incompatible preference type: $key" }
            if (key == K.DOWNLOAD_SPEED_LIMIT) require((value as PreferenceValue.IntValue).value in 0..8)
            if (key == K.PLAYER_SPEED || key == K.LONG_PRESS_SPEED_TIMES) {
                val number = (value as PreferenceValue.StringValue).value.toFloatOrNull()
                require(number != null && number.isFinite() && number > 0)
            }
        }
    }
}
