package app.drivebuddy.spotify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.drivebuddy.core.Pkce
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

class SpotifyAuth(context: Context) {

    companion object {
        const val CLIENT_ID = "c3f90930ad5a4ef4b7ecf26e613d43fc"

        const val PORT = 8888
        const val REDIRECT_URI = "http://127.0.0.1:$PORT/callback"
        private const val SCOPES = "user-modify-playback-state user-library-read user-read-playback-state playlist-read-private playlist-read-collaborative"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        private const val TAG = "DriveBuddy"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("spotify", Context.MODE_PRIVATE)

    init {
        // One-time move of a login saved by older builds from plain text to encrypted storage.
        val oldRefresh = prefs.getString("refresh_token", null)
        if (oldRefresh != null) {
            try {
                prefs.edit()
                    .putString("refresh_token_enc", TokenVault.encrypt(oldRefresh))
                    .remove("refresh_token").remove("access_token").remove("expires_at")
                    .apply()
                Log.i(TAG, "spotify: moved the saved login to encrypted storage")
            } catch (e: Exception) {
                Log.w(TAG, "spotify: could not encrypt the saved login: ${e.javaClass.simpleName}")
            }
        }
    }

    fun isConnected(): Boolean =
        prefs.getString("refresh_token_enc", null)?.let { TokenVault.decrypt(it) } != null

    fun connect(onDone: (Boolean) -> Unit) {
        if (CLIENT_ID.isBlank()) {
            Log.w(TAG, "SpotifyAuth.CLIENT_ID is empty")
            onDone(false)
            return
        }
        val verifier = Pkce.newVerifier()
        val state = Pkce.newVerifier(16)
        val loginUrl = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", Pkce.challenge(verifier))
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .build()

        thread(name = "spotify-login") {
            val ok = try {
                ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                    Handler(Looper.getMainLooper()).post {
                        appContext.startActivity(Intent(Intent.ACTION_VIEW, loginUrl).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    val code = waitForCode(server, state)
                    code != null && exchange(code, verifier)
                }
            } catch (e: IOException) {
                Log.w(TAG, "spotify login failed: ${e.message}")
                false
            }
            onDone(ok)
        }
    }

    fun accessToken(): String? {
        val expiresAt = prefs.getLong("expires_at", 0L)
        val current = prefs.getString("access_token_enc", null)?.let { TokenVault.decrypt(it) }
        if (current != null && System.currentTimeMillis() < expiresAt - 60_000) return current
        val refresh = prefs.getString("refresh_token_enc", null)?.let { TokenVault.decrypt(it) } ?: return null
        val json = postForm(
            mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to CLIENT_ID),
        ) ?: return null
        save(json)
        return json.optString("access_token").takeIf { it.isNotEmpty() }
    }

    private fun waitForCode(server: ServerSocket, state: String): String? {
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            server.soTimeout = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                return null
            }
            val target = try {
                socket.soTimeout = 3_000
                socket.getInputStream().bufferedReader().readLine()?.split(" ")?.getOrNull(1)
            } catch (e: IOException) {
                null
            }
            if (target == null || !target.startsWith("/callback")) {
                socket.close()
                continue
            }
            val uri = Uri.parse("http://127.0.0.1$target")
            val code = uri.getQueryParameter("code")
            val good = code != null && uri.getQueryParameter("state") == state
            val message = if (good) "Spotify connected. You can return to DriveBuddy." else "Spotify connection failed."
            val page = "<html><body style='font-family:sans-serif;font-size:22px;padding:24px'>$message</body></html>"
            val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${page.toByteArray().size}\r\nConnection: close\r\n\r\n$page"
            try {
                socket.getOutputStream().write(response.toByteArray())
            } catch (e: IOException) {
            }
            socket.close()
            return if (good) code else null
        }
        return null
    }

    private fun exchange(code: String, verifier: String): Boolean {
        val json = postForm(
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to REDIRECT_URI,
                "client_id" to CLIENT_ID,
                "code_verifier" to verifier,
            ),
        ) ?: return false
        save(json)
        return true
    }

    private fun save(json: JSONObject) {
        try {
            val editor = prefs.edit()
                .putString("access_token_enc", TokenVault.encrypt(json.getString("access_token")))
                .putLong("expires_at", System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000)
            json.optString("refresh_token").takeIf { it.isNotEmpty() }
                ?.let { editor.putString("refresh_token_enc", TokenVault.encrypt(it)) }
            editor.apply()
            Log.i(TAG, "spotify: tokens saved (encrypted)")
        } catch (e: Exception) {
            Log.w(TAG, "spotify: could not save tokens: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun postForm(params: Map<String, String>): JSONObject? {
        val body = params.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val conn = URL(TOKEN_URL).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 6_000
            conn.readTimeout = 6_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val ok = conn.responseCode in 200..299
            val text = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (ok) JSONObject(text) else {
                Log.w(TAG, "spotify token request failed: ${conn.responseCode} $text")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "spotify token request error: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }
}