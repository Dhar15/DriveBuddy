package app.drivebuddy.spotify

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class SpotifyApi(private val auth: SpotifyAuth) {

    data class Track(val uri: String, val name: String, val artist: String)

    sealed class SearchResult {
        data class Found(val track: Track) : SearchResult()
        object NotFound : SearchResult()
        object NotConnected : SearchResult()
        data class Failed(val why: String) : SearchResult()
    }

    sealed class PlayResult {
        object Started : PlayResult()
        object NoDevice : PlayResult()
        object NotConnected : PlayResult()
        object Refused : PlayResult()
        data class Failed(val why: String) : PlayResult()
    }

    private class Http(val code: Int, val body: String)

    private val api = "https://api.spotify.com/v1"
    private val tag = "DriveBuddy"

    fun search(query: String): SearchResult {
        val token = auth.accessToken() ?: return SearchResult.NotConnected
        val r = call("GET", "$api/search?q=${enc(query)}&type=track&limit=1", token, null)
        if (r.code == 401) return SearchResult.NotConnected
        if (r.code !in 200..299) return SearchResult.Failed("search http ${r.code}")
        return try {
            val items = JSONObject(r.body).getJSONObject("tracks").getJSONArray("items")
            if (items.length() == 0) {
                SearchResult.NotFound
            } else {
                val t = items.getJSONObject(0)
                SearchResult.Found(
                    Track(
                        uri = t.getString("uri"),
                        name = t.getString("name"),
                        artist = t.getJSONArray("artists").getJSONObject(0).getString("name"),
                    ),
                )
            }
        } catch (e: JSONException) {
            SearchResult.Failed("unreadable search response")
        }
    }

    fun play(track: Track, wakeSpotify: () -> Unit): PlayResult {
        val token = auth.accessToken() ?: return PlayResult.NotConnected
        var device = phoneDeviceId(token)
        if (device == null) {
            wakeSpotify()
            repeat(4) {
                if (device == null) {
                    Thread.sleep(1_500)
                    device = phoneDeviceId(token)
                }
            }
        }
        val id = device ?: return PlayResult.NoDevice
        val body = JSONObject().put("uris", JSONArray().put(track.uri)).toString()
        val r = call("PUT", "$api/me/player/play?device_id=${enc(id)}", token, body)
        return when {
            r.code in 200..299 -> PlayResult.Started
            r.code == 401 -> PlayResult.NotConnected
            r.code == 403 -> PlayResult.Refused
            r.code == 404 -> PlayResult.NoDevice
            else -> PlayResult.Failed("play http ${r.code}: ${r.body}")
        }
    }

    private fun phoneDeviceId(token: String): String? {
        val r = call("GET", "$api/me/player/devices", token, null)
        if (r.code !in 200..299) return null
        return try {
            val list = JSONObject(r.body).getJSONArray("devices")
            val phones = (0 until list.length())
                .map { list.getJSONObject(it) }
                .filter { it.optString("type") == "Smartphone" && !it.isNull("id") }
            (phones.firstOrNull { it.optBoolean("is_active") } ?: phones.firstOrNull())?.getString("id")
        } catch (e: JSONException) {
            null
        }
    }

    private fun call(method: String, url: String, token: String, body: String?): Http {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 6_000
            conn.readTimeout = 6_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            Log.i(tag, "spotify $method ${url.substringBefore('?')} -> $code")
            Http(code, text)
        } catch (e: Exception) {
            Log.w(tag, "spotify $method failed: ${e.message}")
            Http(-1, "")
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}