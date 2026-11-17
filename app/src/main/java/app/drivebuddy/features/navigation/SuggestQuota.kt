package app.drivebuddy.features.navigation

import android.content.Context
import android.util.Log

/** Caps place-suggestion lookups per day so a bug can never run up a bill. */
class SuggestQuota(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("suggest_quota", Context.MODE_PRIVATE)

    private fun today() = java.time.LocalDate.now().toString()

    private fun used(): Int = if (prefs.getString("day", null) == today()) prefs.getInt("count", 0) else 0

    fun canUse(): Boolean = used() < LIMIT

    fun record() {
        val n = used() + 1
        prefs.edit().putString("day", today()).putInt("count", n).apply()
        Log.i(TAG, "suggest: $n of $LIMIT lookups used today")
    }

    private companion object {
        const val TAG = "DriveBuddy"
        const val LIMIT = 150
    }
}