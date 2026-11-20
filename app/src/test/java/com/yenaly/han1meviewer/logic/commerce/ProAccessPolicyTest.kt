package com.yenaly.han1meviewer.logic.commerce

import org.junit.Assert.*
import org.junit.Test

class ProAccessPolicyTest {
    @Test fun freeTierKeepsCoreAndPersonalDataAccess() {
        val free = AccessTier.Free
        listOf(ProductRight.BROWSE, ProductRight.PLAY_STREAM, ProductRight.SEARCH,
            ProductRight.BASIC_TRANSLATION, ProductRight.LOCAL_MEDIA_PLAYBACK,
            ProductRight.PERSONAL_DATA_EXPORT, ProductRight.PERSONAL_DATA_RESTORE,
            ProductRight.MANUAL_BACKUP).forEach { assertTrue(ProAccessPolicy.allows(free, it, 1L)) }
        listOf(ProductRight.AUTOMATIC_ORGANIZER, ProductRight.BACKUP_HISTORY,
            ProductRight.WEBDAV_SYNC, ProductRight.BACKGROUND_SYNC,
            ProductRight.ADVANCED_DOWNLOAD_CONCURRENCY,
            ProductRight.AUTOMATED_DOWNLOAD_QUEUE).forEach {
            assertFalse(ProAccessPolicy.allows(free, it, 1L))
        }
    }

    @Test fun trialAndLicenseExpireWithoutLockingPersonalFiles() {
        val trial = AccessTier.Trial(1000L)
        val paid = AccessTier.Pro(1000L)
        for (tier in listOf(trial, paid)) {
            assertTrue(ProAccessPolicy.allows(tier, ProductRight.AUTOMATIC_BACKUP, 999L))
            assertFalse(ProAccessPolicy.allows(tier, ProductRight.AUTOMATIC_BACKUP, 1000L))
            assertTrue(ProAccessPolicy.allows(tier, ProductRight.PERSONAL_DATA_EXPORT, 1000L))
        }
    }

    @Test fun existingUsersKeepAccessDuringTransition() {
        ProductRight.entries.forEach { assertTrue(ProAccessPolicy.allows(AccessTier.ExistingUser, it, Long.MAX_VALUE)) }
    }
}
