package app.drivebuddy

import android.content.Context
import app.drivebuddy.core.Headline

/** Remembers which articles were already read out, so the next request brings new ones. Forgets after a day. */
class NewsSeen(context: Context) {
    private val prefs = context.getSharedPreferences("news_seen", Context.MODE_PRIVATE)

    fun links(): Set<String> {
        val cutoff = System.currentTimeMillis() - DAY_MS
        val edit = prefs.edit()
        prefs.all.forEach { (link, time) -> if ((time as? Long ?: 0L) < cutoff) edit.remove(link) }
        edit.apply()
        return prefs.all.keys.toSet()
    }

    fun mark(items: List<Headline>) {
        val now = System.currentTimeMillis()
        val edit = prefs.edit()
        items.forEach { edit.putLong(it.link, now) }
        edit.apply()
    }

    private companion object { const val DAY_MS = 24 * 60 * 60 * 1000L }
}