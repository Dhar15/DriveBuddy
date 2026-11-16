package app.drivebuddy.features.learning

import android.os.SystemClock
import android.util.Log
import app.drivebuddy.BuildConfig
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

data class GkFact(val category: String, val fact: String)

/** Today's GK fact from the same Supabase table Sprint uses. Read-only. Call off the main thread. */
object GkClient {
    private const val TAG = "DriveBuddy"

    fun fetch(): GkFact? {
        val base = BuildConfig.SUPABASE_URL
        val key = BuildConfig.SUPABASE_ANON_KEY
        if (base.isBlank() || key.isBlank()) {
            Log.w(TAG, "gk: SUPABASE_URL or SUPABASE_ANON_KEY is not set in local.properties")
            return null
        }
        val started = SystemClock.elapsedRealtime()
        val conn = try {
            URL("$base/rest/v1/gk_facts?select=id,category,fact&active=eq.true&order=id.asc")
                .openConnection() as HttpURLConnection
        } catch (e: Exception) {
            Log.w(TAG, "gk: bad SUPABASE_URL: ${e.javaClass.simpleName}")
            return null
        }
        return try {
            conn.connectTimeout = 6_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("apikey", key)
            conn.setRequestProperty("Authorization", "Bearer $key")
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            Log.i(TAG, "gk: HTTP $code in ${SystemClock.elapsedRealtime() - started}ms")
            if (code !in 200..299) {
                Log.w(TAG, "gk: request failed: ${text.take(120)}")
                return null
            }
            val facts = JSONArray(text)
            // Same rule as Sprint: day of the year, counted from 0 on 1 January.
            val index = DailyGk.indexFor(LocalDate.now().dayOfYear - 1, facts.length())
            if (index < 0) {
                Log.w(TAG, "gk: no active facts")
                return null
            }
            Log.i(TAG, "gk: ${facts.length()} facts, today is #$index")
            val item = facts.getJSONObject(index)
            GkFact(item.optString("category"), item.getString("fact"))
        } catch (e: Exception) {
            Log.w(TAG, "gk: failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }
}