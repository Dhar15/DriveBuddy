package app.drivebuddy.features.learning

import android.os.SystemClock
import android.util.Log
import app.drivebuddy.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class WordOfDay(val word: String, val meaning: String, val example: String)

/** Today's word from the same Google Apps Script that feeds the Sprint app. Call off the main thread. */
object WordOfDayClient {
    private const val TAG = "DriveBuddy"

    fun fetch(readTimeoutMs: Int = 10_000): WordOfDay? {
        val base = BuildConfig.WORD_API_URL
        if (base.isBlank()) {
            Log.w(TAG, "word: WORD_API_URL is not set in local.properties")
            return null
        }
        val started = SystemClock.elapsedRealtime()
        val conn = try {
            URL("$base?action=word_of_day").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            Log.w(TAG, "word: bad WORD_API_URL: ${e.javaClass.simpleName}")
            return null
        }
        return try {
            conn.connectTimeout = 6_000
            conn.readTimeout = readTimeoutMs    // Apps Script can be slow on a cold start
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            Log.i(TAG, "word: HTTP $code in ${SystemClock.elapsedRealtime() - started}ms")
            if (code !in 200..299) return null
            val json = JSONObject(text)
            if (!json.optBoolean("success")) {
                Log.w(TAG, "word: the service did not report success")
                return null
            }
            val w = json.getJSONObject("word")
            WordOfDay(w.getString("word"), w.getString("meaning"), w.optString("example", ""))
        } catch (e: Exception) {
            Log.w(TAG, "word: failed after ${SystemClock.elapsedRealtime() - started}ms: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }
}