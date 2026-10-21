package app.drivebuddy.audio

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * One-shot speech recognition. Must be used from the main thread.
 * Prefers the on-device recogniser (Android 13+), retries it once, then falls back to the system default.
 */
class SpeechCapture(private val context: Context) {

    interface Callback {
        fun onListening()
        fun onSpeechEnd()
        fun onResult(text: String)
        fun onError(message: String)
    }

    private var recognizer: SpeechRecognizer? = null

    private val handler = Handler(Looper.getMainLooper())
    private var startupWatchdog: Runnable? = null
    private var session = 0 // bumped on every begin()/destroy(); stale callbacks compare against it

    private companion object { const val RESTART_DELAY_MS = 400L }

    /** "on-device", "system-default", or "unavailable" - recorded in the latency log. */
    var engineName: String = "unavailable"
        private set

    // Errors where the on-device recogniser itself is unusable (busy, disconnected, language pack missing).
    private val retryable = setOf(
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_CLIENT,
    )

    fun start(callback: Callback) = begin(callback, forceSystem = false, sameEngineRetryLeft = true)

    private fun begin(callback: Callback, forceSystem: Boolean, sameEngineRetryLeft: Boolean) {
        destroy()
        val id = ++session

        val onDevice = !forceSystem && Build.VERSION.SDK_INT >= 33 &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        val created: SpeechRecognizer? = when {
            onDevice -> {
                engineName = "on-device"
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            }
            SpeechRecognizer.isRecognitionAvailable(context) -> {
                engineName = "system-default"
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            else -> null
        }

        if (created == null) {
            engineName = "unavailable"
            callback.onError("No speech recogniser available on this device")
            return
        }

        recognizer = created
        var endReported = false // some recognisers call onEndOfSpeech twice
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (id != session) return
                cancelWatchdog()
                Log.i("DriveBuddy", "recogniser ready on $engineName")
                callback.onListening()
            }

            override fun onBeginningOfSpeech() {
                if (id == session) Log.i("DriveBuddy", "speech began")
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                if (id != session || endReported) return
                endReported = true
                Log.i("DriveBuddy", "speech ended")
                callback.onSpeechEnd()
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                if (id != session) return
                cancelWatchdog()
                val wasOnDevice = engineName == "on-device"
                Log.w("DriveBuddy", "recogniser error $error (${describe(error)}) on $engineName")
                if (wasOnDevice && error in retryable) {
                    // Don't touch the recogniser from inside its own callback: step out first, then tear down and restart.
                    val toSystem = !sameEngineRetryLeft
                    handler.post {
                        if (id == session) restartAfterPause(callback, forceSystem = toSystem, why = "error ${describe(error)}")
                    }
                    return
                }
                callback.onError(describe(error))
                releaseSoon(id)
            }

            override fun onResults(results: Bundle?) {
                if (id != session) return
                cancelWatchdog()
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                callback.onResult(text)
                releaseSoon(id)
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        // If the recogniser never reports "ready" within 3 s, treat it as a failed start.
        val wd = Runnable {
            if (id != session) return@Runnable
            Log.w("DriveBuddy", "recogniser never became ready on $engineName")
            if (engineName == "on-device") {
                restartAfterPause(callback, forceSystem = !sameEngineRetryLeft, why = "never ready")
            } else {
                destroy()
                callback.onError("recogniser did not start")
            }
        }
        startupWatchdog = wd
        handler.postDelayed(wd, 3_000)

        created.startListening(intent)
    }

    /**
     * Releases the current recogniser first, waits so the speech service can actually shut it down,
     * then starts a fresh one. Creating a recogniser straight after destroying another caused the
     * "disconnected" and "busy" errors.
     */
    private fun restartAfterPause(callback: Callback, forceSystem: Boolean, why: String) {
        destroy() // also cancels any older pending work
        val token = session
        Log.i("DriveBuddy", "recogniser: $why; restarting on ${if (forceSystem) "system default" else "on-device"} in $RESTART_DELAY_MS ms")
        handler.postDelayed({ if (token == session) begin(callback, forceSystem, sameEngineRetryLeft = false) }, RESTART_DELAY_MS)
    }

    /** Frees this interaction's recogniser once it has finished, so the next one never starts on a stale one. */
    private fun releaseSoon(id: Int) {
        handler.post {
            if (id == session) {
                Log.d("DriveBuddy", "recogniser released")
                destroy()
            }
        }
    }

    private fun cancelWatchdog() {
        startupWatchdog?.let { handler.removeCallbacks(it) }
        startupWatchdog = null
    }

    fun destroy() {
        cancelWatchdog()
        session++ // invalidate any in-flight callbacks and pending retries
        recognizer?.destroy()
        recognizer = null
    }

    private fun describe(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "audio error"
        SpeechRecognizer.ERROR_CLIENT -> "client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission missing"
        SpeechRecognizer.ERROR_NETWORK -> "network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "no match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recogniser busy"
        SpeechRecognizer.ERROR_SERVER -> "server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech heard"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "too many requests"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "recogniser disconnected"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language not supported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language pack unavailable"
        else -> "error $code"
    }
}