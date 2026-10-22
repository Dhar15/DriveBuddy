package app.drivebuddy.spotify

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import app.drivebuddy.core.TrackCandidate
import app.drivebuddy.core.TrackPicker

class SpotifyApi(private val auth: SpotifyAuth) {

//    data class Track(val uri: String, val name: String, val artist: String)
    data class Track(
        val uri: String,
        val name: String,
        val artist: String,
        val albumUri: String
    )
    sealed class SearchResult {
        data class Found(val track: Track) : SearchResult()
        object NotFound : SearchResult()
        object NotConnected : SearchResult()
        data class Failed(val why: String) : SearchResult()
    }

    sealed class PlayResult {
        object Started : PlayResult()
        object NoDevice : PlayResult()
        object NotPlaying : PlayResult()
        object NotConnected : PlayResult()
        object Refused : PlayResult()
        data class Failed(val why: String) : PlayResult()
    }

    private class Http(val code: Int, val body: String)

    private val api = "https://api.spotify.com/v1"
    private val tag = "DriveBuddy"

    fun search(query: String): SearchResult {
        val token = auth.accessToken() ?: return SearchResult.NotConnected
        val r = call("GET", "$api/search?q=${enc(query)}&type=track&limit=10", token, null)
        if (r.code == 401) return SearchResult.NotConnected
        if (r.code !in 200..299) return SearchResult.Failed("search http ${r.code}")
        return try {
            val items = JSONObject(r.body).getJSONObject("tracks").getJSONArray("items")
            if (items.length() == 0) {
                SearchResult.NotFound
            } else {
                val candidates = (0 until items.length()).map { i ->
                    val t = items.getJSONObject(i)
                    val artists = t.getJSONArray("artists")
                    TrackCandidate(
                        name = t.getString("name"),
                        artists = (0 until artists.length()).map { artists.getJSONObject(it).getString("name") },
                        popularity = t.optInt("popularity", 0),
                    )
                }
                val pick = TrackPicker.best(query, candidates)
                Log.i(tag, "spotify: search \"$query\" -> ${candidates.size} results, picked #${pick + 1} " +
                        "\"${candidates[pick].name}\" by ${candidates[pick].artists.firstOrNull()}; " +
                        "Spotify's first was \"${candidates[0].name}\"")
                val t = items.getJSONObject(pick)
                SearchResult.Found(
                    Track(
                        uri = t.getString("uri"),
                        name = t.getString("name"),
                        artist = t.getJSONArray("artists")
                            .getJSONObject(0)
                            .getString("name"),
                        albumUri = t.getJSONObject("album").getString("uri")
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
        val body = JSONObject()
            .put("context_uri", track.albumUri)
            .put("offset", JSONObject().put("uri", track.uri))
            .toString()

        Log.i(tag, "spotify: sending play to device ending ${id.takeLast(4)} for \"${track.name}\"")
        val r = call("PUT", "$api/me/player/play?device_id=${enc(id)}", token, body)
        return when {
            r.code in 200..299 -> if (verifyPlaying(token, track)) PlayResult.Started else PlayResult.NotPlaying
            r.code == 401 -> PlayResult.NotConnected
            r.code == 403 -> PlayResult.Refused
            r.code == 404 -> PlayResult.NoDevice
            else -> PlayResult.Failed("play http ${r.code}: ${r.body}")
        }
    }

    /** After a play command, confirm Spotify is really playing the track. Polls up to 3 times, 1s apart. */
    private fun verifyPlaying(token: String, track: Track): Boolean {
        repeat(3) { attempt ->
            Thread.sleep(1_000)
            val r = call("GET", "$api/me/player", token, null)
            if (r.code == 200) {
                try {
                    val s = JSONObject(r.body)
                    val item = s.optJSONObject("item")
                    val playing = s.optBoolean("is_playing")
                    val uri = item?.optString("uri").orEmpty()
                    val name = item?.optString("name").orEmpty()
                    Log.i(tag, "spotify: verify #${attempt + 1} playing=$playing track=\"$name\" wanted=\"${track.name}\"")
                    if (playing && (uri == track.uri || name.equals(track.name, ignoreCase = true))) return true
                } catch (e: JSONException) {
                    Log.w(tag, "spotify: verify #${attempt + 1} unreadable player state")
                }
            } else {
                Log.i(tag, "spotify: verify #${attempt + 1} player http ${r.code} (no active player)")
            }
        }
        Log.w(tag, "spotify: play accepted but playback never started for \"${track.name}\"")
        return false
    }

    private fun phoneDeviceId(token: String): String? {
        val r = call("GET", "$api/me/player/devices", token, null)
        if (r.code !in 200..299) return null
        return try {
            val list = JSONObject(r.body).getJSONArray("devices")
            (0 until list.length()).map { list.getJSONObject(it) }.forEach {
                Log.i(tag, "spotify device: \"${it.optString("name")}\" type=${it.optString("type")} " +
                        "active=${it.optBoolean("is_active")} restricted=${it.optBoolean("is_restricted")} " +
                        "idEnds=${it.optString("id").takeLast(4)} volume=${it.optInt("volume_percent", -1)}")
            }
            Log.i(tag, "spotify devices: " + list.toString())
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