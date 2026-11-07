package app.drivebuddy

import android.util.Log
import app.drivebuddy.core.ChatTurn
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** One call to Groq's OpenAI-compatible chat endpoint. Blocking: call it off the main thread. */
class LlmClient(private val apiKey: String = BuildConfig.GROQ_API_KEY) {

    sealed class Result {
        data class Reply(val text: String) : Result()
        object NotConfigured : Result()
        object RateLimited : Result()
        object Offline : Result()
        data class Failed(val why: String) : Result()
    }

    fun chat(messages: List<ChatTurn>): Result {
        if (apiKey.isBlank()) {
            Log.w(TAG, "llm: no GROQ_API_KEY configured")
            return Result.NotConfigured
        }
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_completion_tokens", MAX_COMPLETION_TOKENS)
            .put("reasoning_effort", "low")
            .put("include_reasoning", false)
            .put("temperature", 0.7)
            .put("messages", JSONArray().also { array ->
                messages.forEach { array.put(JSONObject().put("role", it.role).put("content", it.content)) }
            })
            .toString()

        val started = System.currentTimeMillis()
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "DriveBuddy/1.0") // the default Java agent can be refused by the provider's edge
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val took = System.currentTimeMillis() - started
            when {
                code == 429 -> {
                    Log.w(TAG, "llm: rate limited after ${took}ms, retry-after=${conn.getHeaderField("retry-after")}")
                    Result.RateLimited
                }
                code == 401 || code == 403 -> {
                    Log.w(TAG, "llm: key rejected (http $code): ${errorMessage(text)}")
                    Result.Failed("key rejected")
                }
                code !in 200..299 -> {
                    Log.w(TAG, "llm: http $code after ${took}ms: ${errorMessage(text)}")
                    Result.Failed("http $code")
                }
                else -> parse(text, took)
            }
        } catch (e: UnknownHostException) {
            Log.w(TAG, "llm: offline (${e.javaClass.simpleName})")
            Result.Offline
        } catch (e: ConnectException) {
            Log.w(TAG, "llm: offline (${e.javaClass.simpleName})")
            Result.Offline
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "llm: timed out after ${System.currentTimeMillis() - started}ms")
            Result.Failed("timed out")
        } catch (e: Exception) {
            Log.w(TAG, "llm: failed: ${e.javaClass.simpleName}")
            Result.Failed(e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: String, took: Long): Result = try {
        val choice = JSONObject(json).getJSONArray("choices").getJSONObject(0)
        val message = choice.getJSONObject("message")
        val reply = if (message.isNull("content")) "" else message.getString("content").trim()
        if (reply.isEmpty()) Log.w(TAG, "llm: empty answer, finish_reason=${choice.optString("finish_reason")}")
        Log.i(TAG, "llm: reply of ${reply.length} characters in ${took}ms") // length only, never the words
        if (reply.isEmpty()) Result.Failed("empty reply") else Result.Reply(reply)
    } catch (e: JSONException) {
        Log.w(TAG, "llm: unreadable response")
        Result.Failed("unreadable response")
    }

    /** The provider's own explanation of an error. It describes the problem, never your words. */
    private fun errorMessage(json: String): String = try {
        JSONObject(json).getJSONObject("error").optString("message").take(200)
    } catch (e: JSONException) {
        "no details"
    }

    private companion object {
        const val TAG = "DriveBuddy"
        const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        const val MODEL = "openai/gpt-oss-120b"
        const val MAX_COMPLETION_TOKENS = 400
    }
}