package com.yenaly.han1meviewer.ui.screen.search

import androidx.test.platform.app.InstrumentationRegistry
import com.yenaly.han1meviewer.logic.translation.*
import org.junit.Assert.*
import org.junit.Test

class SearchEnhancementsDeviceTest {
    @Test fun aliasesHistoryBoundsRemovalAndUnreadableDataProtection() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("search_translation_tools", 0)
        prefs.edit().clear().commit()
        try {
            val store = SearchToolsStore(context)
            store.saveAlias("Title", "作品")
            store.saveAlias(" TITLE ", "作品２")
            assertEquals(1, store.read().aliases.size)
            repeat(55) { store.remember("query$it", "translation$it") }
            assertEquals(50, store.read().history.size)
            val first = store.read().history.first()
            store.remove(first, false)
            assertEquals(49, store.read().history.size)
            store.clearHistory()
            assertTrue(store.read().history.isEmpty())
            assertEquals("作品２", store.read().aliases.single().translated)
            prefs.edit().putString("data", "corrupt").commit()
            try { store.saveAlias("new", "value"); fail() } catch (_: Exception) { }
            assertEquals("corrupt", prefs.getString("data", null))
        } finally { prefs.edit().clear().commit() }
    }
}
