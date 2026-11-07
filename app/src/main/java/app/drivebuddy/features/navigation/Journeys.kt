package app.drivebuddy.features.navigation

import android.content.Context
import android.text.format.DateUtils
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** One trip the app started: what you asked for, what Maps was given, and when. */
data class Trip(val name: String, val query: String, val atMillis: Long)

/** The last few trips, newest first, kept on the phone. Only trips the app really started are recorded. */
object Journeys {
    private const val TAG = "DriveBuddy"
    private const val PREFS = "journeys"
    private const val KEY = "list"
    private const val MAX = 10

    fun all(context: Context): List<Trip> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map {
                val o = array.getJSONObject(it)
                Trip(o.getString("name"), o.getString("query"), o.getLong("at"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "journeys: stored list unreadable, treating as empty", e)
            emptyList()
        }
    }

    /** Adds a trip at the top. Going to the same place again moves it up instead of listing it twice. */
    fun record(context: Context, name: String, query: String) {
        val label = name.trim().replaceFirstChar { it.uppercase() }
        if (label.isEmpty()) return
        val trip = Trip(label, query.trim(), System.currentTimeMillis())
        val kept = (listOf(trip) + all(context)).distinctBy { it.name.lowercase() }.take(MAX)
        val array = JSONArray()
        kept.forEach { array.put(JSONObject().put("name", it.name).put("query", it.query).put("at", it.atMillis)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
        Log.i(TAG, "journeys: recorded \"$label\", ${kept.size} kept")
    }

    /** "Today, 8:42 AM" or "12 Oct, 8:42 AM". */
    fun whenText(context: Context, millis: Long): String {
        val time = DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME)
        if (DateUtils.isToday(millis)) return "Today, $time"
        val day = DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
        return "$day, $time"
    }
}