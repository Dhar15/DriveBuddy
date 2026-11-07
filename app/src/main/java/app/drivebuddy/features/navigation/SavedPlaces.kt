package app.drivebuddy.features.navigation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log

/** One saved place: what you say ("Home") and where it points ("Samhita Rainbow MTB"). */
data class Place(val name: String, val address: String)

/**
 * Your saved places, stored on the phone so the app can add, change and delete them.
 * First launch starts from [defaults]; after that the stored list is the truth, even if empty.
 */
object SavedPlaces {
    private const val PREFS = "saved_places"
    private const val KEY = "list"
    private const val TAG = "DriveBuddy"

    private val defaults = listOf(
        Place("Home", "Samhita Rainbow MTB"),
        Place("Office", "Wipro - Kodathi"),
        Place("Gym", "Cult Fit Kundalahalli"),
    )

    fun all(context: Context): List<Place> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null)
        if (raw == null) {
            Log.i(TAG, "places: first launch, seeded ${defaults.size} starter places")
            write(context, defaults) // first launch: keep the starter list so later deletes stick
            return defaults
        }
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map {
                val o = array.getJSONObject(it)
                Place(o.getString("name"), o.getString("address"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "places: stored list unreadable, using starter list", e)
            defaults // unreadable data: show the starter list instead of crashing
        }
    }

    /**
     * Adds a place, or updates one. Pass [replacing] (the old name) when editing, so a rename
     * changes the existing entry instead of adding a second one. Returns false if name or address is blank.
     */
    fun save(context: Context, name: String, address: String, replacing: String? = null): Boolean {
        val n = name.trim()
        val a = address.trim()
        if (n.isEmpty() || a.isEmpty()) {
            Log.w(TAG, "places: save rejected, blank name or address")
            return false
        }
        val list = all(context).toMutableList()
        val placed = Place(n, a)
        val index = list.indexOfFirst { it.name.equals(replacing ?: n, ignoreCase = true) }
        if (index >= 0) list[index] = placed else list.add(placed)
        list.removeAll { it.name.equals(n, ignoreCase = true) && it !== placed } // no duplicate names
        write(context, list)
        Log.i(TAG, "places: ${if (index >= 0) "updated" else "added"} \"$n\", total ${list.size}")
        return true
    }

    fun delete(context: Context, name: String) {
        val before = all(context)
        val after = before.filterNot { it.name.equals(name, ignoreCase = true) }
        write(context, after)
        Log.i(TAG, "places: deleted \"$name\" (${before.size} -> ${after.size})")
    }

    /** Turns a spoken name into an address. Anything that isn't saved is searched as spoken. */
    fun resolve(context: Context, spoken: String): String {
        val hit = all(context).firstOrNull { it.name.equals(spoken.trim(), ignoreCase = true) }
        Log.i(TAG, "places: \"$spoken\" -> ${if (hit != null) "saved place" else "searched as spoken"}")
        return hit?.address ?: spoken
    }

    private fun write(context: Context, list: List<Place>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("name", it.name).put("address", it.address)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}