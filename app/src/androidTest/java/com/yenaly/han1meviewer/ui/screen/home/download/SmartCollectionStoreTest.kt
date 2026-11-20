package com.yenaly.han1meviewer.ui.screen.home.download

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.yenaly.han1meviewer.logic.library.MediaLibraryCodec
import com.yenaly.han1meviewer.logic.library.MediaLibraryRule
import com.yenaly.han1meviewer.logic.library.SmartCollection
import com.yenaly.han1meviewer.logic.library.SmartCollectionStore
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SmartCollectionStoreTest {
    @Test fun persistenceRenameDeleteAndExternalBackupRestore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("library-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val first = SmartCollection("a", "Original", MediaLibraryRule(keyword = "alpha"))
        try {
            SmartCollectionStore(preferences).use { store -> assertTrue(store.save(first)) }
            SmartCollectionStore(preferences).use { store ->
                assertEquals(listOf(first), store.catalog.value.collections)
                assertTrue(store.save(first.copy(name = "Renamed")))
                assertEquals(1, store.catalog.value.collections.size)
                assertEquals("Renamed", store.catalog.value.collections.single().name)
                assertFalse(store.save(first.copy(id = "b", name = "renamed")))
                assertTrue(store.delete("a"))
                assertTrue(store.catalog.value.collections.isEmpty())
                // Same settings write used by BackupManager must update an already-open library.
                preferences.edit().putString(SmartCollectionStore.KEY, MediaLibraryCodec.encodeCollections(listOf(first))).commit()
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(listOf(first), store.catalog.value.collections)
            }
        } finally { preferences.edit().clear().commit() }
    }

    @Test fun corruptedCatalogIsPreservedAndCannotBeOverwrittenBySaving() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("library-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        try {
            preferences.edit().putString(SmartCollectionStore.KEY, "unreadable").commit()
            SmartCollectionStore(preferences).use { store ->
                assertTrue(store.catalog.value.readError)
                assertFalse(store.save(SmartCollection("a", "New", MediaLibraryRule())))
                assertFalse(store.delete("a"))
                assertEquals("unreadable", preferences.getString(SmartCollectionStore.KEY, null))
            }
        } finally { preferences.edit().clear().commit() }
    }
}
