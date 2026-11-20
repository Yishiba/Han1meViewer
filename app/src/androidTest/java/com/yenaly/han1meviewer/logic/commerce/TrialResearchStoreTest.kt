package com.yenaly.han1meviewer.logic.commerce

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class TrialResearchStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun isolated(existing: Boolean, test: (TrialResearchStore) -> Unit) {
        val general = PreferenceManager.getDefaultSharedPreferences(context)
        val prior = general.getBoolean("usage_notice_accepted", false)
        val hadPrior = general.contains("usage_notice_accepted")
        val research = context.getSharedPreferences("trial_research_device", Context.MODE_PRIVATE)
        val snapshot = research.all.toMap()
        try {
            research.edit().clear().commit()
            general.edit().putBoolean("usage_notice_accepted", existing).commit()
            test(TrialResearchStore(context))
        } finally {
            val restore = research.edit().clear()
            snapshot.forEach { (key, value) -> when (value) {
                is String -> restore.putString(key, value)
                is Int -> restore.putInt(key, value)
                is Long -> restore.putLong(key, value)
            } }
            restore.commit()
            val editor = general.edit()
            if (hadPrior) editor.putBoolean("usage_notice_accepted", prior) else editor.remove("usage_notice_accepted")
            editor.commit()
        }
    }

    @Test fun newUserStartsOnlyAfterSuccessfulHomeLoadAndNeverRestarts() = isolated(false) { store ->
        assertFalse(store.isExistingUser)
        assertEquals(0L, store.startedAtMillis)
        assertNull(store.daysRemaining())
        assertEquals(30, store.quoteYuan)
        store.recordSuccessfulHomeLoad(1_000_000L)
        assertEquals(14, store.daysRemaining(1_000_000L))
        store.recordSuccessfulHomeLoad(2_000_000L)
        assertEquals(1_000_000L, TrialResearchStore(context).startedAtMillis)
        assertEquals(0, store.daysRemaining(1_000_000L + 15 * 86_400_000L))
        store.recordAnswer("consider")
        assertEquals("consider", TrialResearchStore(context).answer)
    }

    @Test fun existingUserIsExemptEvenAfterHomeLoads() = isolated(true) { store ->
        assertTrue(store.isExistingUser)
        store.recordSuccessfulHomeLoad(1_000_000L)
        assertEquals(0L, store.startedAtMillis)
        assertNull(store.daysRemaining())
    }

    @Test fun priorResearchQuoteIsPreserved() = isolated(false) {
        context.getSharedPreferences("trial_research_device", Context.MODE_PRIVATE)
            .edit().putInt("quote_yuan", 49).commit()
        assertEquals(49, TrialResearchStore(context).quoteYuan)
    }
}
