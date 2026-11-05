package app.drivebuddy

import android.content.Context
import android.util.Log
import app.drivebuddy.core.DailyCap
import app.drivebuddy.core.QuotaState
import java.time.LocalDate

/** Remembers how many chats were used today, so a bug can never burn the free allowance. */
class ChatQuota(context: Context, private val limit: Int = DAILY_LIMIT) {
    private val prefs = context.getSharedPreferences("chat_quota", Context.MODE_PRIVATE)

    private fun state(): QuotaState? =
        prefs.getString("day", null)?.let { QuotaState(it, prefs.getInt("used", 0)) }

    fun canUse(): Boolean = DailyCap.canUse(state(), LocalDate.now().toString(), limit)

    fun record() {
        val next = DailyCap.afterUse(state(), LocalDate.now().toString())
        prefs.edit().putString("day", next.day).putInt("used", next.used).apply()
        Log.i(TAG, "chat: ${next.used} of $limit used today")
    }

    private companion object {
        const val TAG = "DriveBuddy"
        const val DAILY_LIMIT = 100
    }
}