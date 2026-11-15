package app.drivebuddy.features.navigation

import android.content.Context
import android.util.Log
import java.time.LocalDate

/** At most [LIMIT] route lookups a day. */
class EtaQuota(context: Context) {

    companion object {
        const val LIMIT = 50
        private const val TAG = "DriveBuddy"
    }

    private val prefs = context.getSharedPreferences("eta_quota", Context.MODE_PRIVATE)

    private fun used(today: String): Int =
        if (prefs.getString("day", "") == today) prefs.getInt("count", 0) else 0

    fun canUse(): Boolean = used(LocalDate.now().toString()) < LIMIT

    fun record() {
        val today = LocalDate.now().toString()
        val count = used(today) + 1
        prefs.edit().putString("day", today).putInt("count", count).apply()
        Log.i(TAG, "eta: $count of $LIMIT lookups used today")
    }
}