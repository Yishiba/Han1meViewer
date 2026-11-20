package com.yenaly.han1meviewer.logic.commerce

/** Product rights only. Runtime enforcement stays disabled until a verified seller and license service exist. */
enum class ProductRight {
    BROWSE, PLAY_STREAM, SEARCH, BASIC_TRANSLATION,
    LOCAL_MEDIA_PLAYBACK, PERSONAL_DATA_EXPORT, PERSONAL_DATA_RESTORE,
    BASIC_LIBRARY_FILTER, MANUAL_BACKUP,
    ADVANCED_DOWNLOAD_CONCURRENCY, AUTOMATED_DOWNLOAD_QUEUE,
    SMART_COLLECTIONS, AUTOMATIC_ORGANIZER, LIBRARY_INSPECTION,
    BACKUP_HISTORY, AUTOMATIC_BACKUP, ENCRYPTED_BACKUP,
    WEBDAV_MEDIA, WEBDAV_SYNC, BACKGROUND_SYNC, SAVED_SEARCH_UPDATES,
}

sealed interface AccessTier {
    data object Free : AccessTier
    data object ExistingUser : AccessTier
    data class Trial(val expiresAtMillis: Long) : AccessTier
    data class Pro(val expiresAtMillis: Long) : AccessTier
}

object ProAccessPolicy {
    private val freeRights = setOf(
        ProductRight.BROWSE, ProductRight.PLAY_STREAM, ProductRight.SEARCH,
        ProductRight.BASIC_TRANSLATION, ProductRight.LOCAL_MEDIA_PLAYBACK,
        ProductRight.PERSONAL_DATA_EXPORT, ProductRight.PERSONAL_DATA_RESTORE,
        ProductRight.BASIC_LIBRARY_FILTER, ProductRight.MANUAL_BACKUP,
    )

    fun allows(tier: AccessTier, right: ProductRight, nowMillis: Long): Boolean =
        right in freeRights || when (tier) {
            AccessTier.Free -> false
            AccessTier.ExistingUser -> true // Transitional policy; existing installs are never locked by this rollout.
            is AccessTier.Trial -> nowMillis < tier.expiresAtMillis
            is AccessTier.Pro -> nowMillis < tier.expiresAtMillis
        }
}
