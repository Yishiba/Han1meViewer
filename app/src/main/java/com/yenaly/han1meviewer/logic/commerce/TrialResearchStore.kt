package com.yenaly.han1meviewer.logic.commerce

import android.content.Context
import com.yenaly.han1meviewer.Preferences

/** Local pricing research only. This state never grants or revokes access. */
class TrialResearchStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("trial_research_device", Context.MODE_PRIVATE)

    init {
        if (!prefs.contains("cohort")) {
            val existingUser = Preferences.usageNoticeAccepted
            prefs.edit().putString("cohort", if (existingUser) "existing" else "new")
                .putInt("quote_yuan", 30).commit()
        }
    }

    val isExistingUser: Boolean get() = prefs.getString("cohort", null) == "existing"
    val quoteYuan: Int get() = prefs.getInt("quote_yuan", 30)
    val startedAtMillis: Long get() = prefs.getLong("started_at", 0L)
    val answer: String? get() = prefs.getString("answer", null)

    fun recordSuccessfulHomeLoad(nowMillis: Long = System.currentTimeMillis()) {
        if (!isExistingUser && startedAtMillis == 0L) {
            prefs.edit().putLong("started_at", nowMillis).commit()
        }
    }

    fun recordAnswer(value: String) {
        require(value in setOf("consider", "conditional", "decline", "undecided"))
        prefs.edit().putString("answer", value).commit()
    }

    fun daysRemaining(nowMillis: Long = System.currentTimeMillis()): Int? {
        val started = startedAtMillis
        if (isExistingUser || started == 0L) return null
        val elapsed = ((nowMillis - started).coerceAtLeast(0L) / DAY_MILLIS)
        return (14L - elapsed).coerceAtLeast(0L).toInt()
    }

    private companion object { const val DAY_MILLIS = 86_400_000L }
}
