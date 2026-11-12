package app.drivebuddy.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import android.util.Log
import app.drivebuddy.common.LogText

/** Thin wrapper over Android's built-in TextToSpeech. Callbacks are delivered on the main thread. */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private val main = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext
    private var tts = TextToSpeech(appContext, this)
    private var afterRebuild: (() -> Unit)? = null
    private var giveUp: (() -> Unit)? = null
    private var ready = false
    private var currentId: String? = null
    private var startCallback: (() -> Unit)? = null
    private var doneCallback: (() -> Unit)? = null
    private var progressCallback: ((Int) -> Unit)? = null
    private var progressSeen = false

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e("DriveBuddy", "TTS initialization failed: status=$status")
            afterRebuild = null
            giveUp?.let { giveUp = null; main.post(it) }
            return
        }
        val languageStatus = tts.setLanguage(Locale("en", "IN"))
        Log.i("DriveBuddy", "TTS language status=$languageStatus")

        if (languageStatus == TextToSpeech.LANG_MISSING_DATA ||
            languageStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e("DriveBuddy", "TTS language unavailable: en-IN")
        }

        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {

            override fun onStart(utteranceId: String?) {
                Log.i("DriveBuddy", "TTS onStart: id=$utteranceId")
                main.post {
                    if (utteranceId == currentId) {
                        startCallback?.invoke()
                    }
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                if (!progressSeen) {
                    progressSeen = true
                    Log.i("DriveBuddy", "TTS progress callbacks working, first at character $start")
                }
                main.post {
                    if (utteranceId == currentId) progressCallback?.invoke(start)
                }
            }

            override fun onDone(utteranceId: String?) {
                Log.i("DriveBuddy", "TTS onDone: id=$utteranceId")
                main.post {
                    if (utteranceId == currentId) finish()
                }
            }

            @Deprecated("Required by older Android APIs")
            override fun onError(utteranceId: String?) {
                Log.e("DriveBuddy", "TTS onError: id=$utteranceId")
                main.post {
                    if (utteranceId == currentId) finish()
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(
                    "DriveBuddy",
                    "TTS onError: id=$utteranceId, errorCode=$errorCode"
                )
                main.post {
                    if (utteranceId == currentId) finish()
                }
            }
        })

        ready = true
        afterRebuild?.let { retry -> afterRebuild = null; giveUp = null; main.post(retry) }
    }

    /** Speaks [text]. [onStart] fires when audio actually begins; [onDone] when it ends or fails. */
    /** Speaks [text]. [onStart] fires when audio actually begins; [onDone] when it ends or fails. */
    fun speak(text: String, onStart: () -> Unit, onDone: () -> Unit, onProgress: ((Int) -> Unit)? = null, quiet: Boolean = false) =
        attempt(text, onStart, onDone, onProgress, quiet, canRebuild = true)

    private fun attempt(
        text: String, onStart: () -> Unit, onDone: () -> Unit,
        onProgress: ((Int) -> Unit)?, quiet: Boolean, canRebuild: Boolean,
    ) {
        Log.i("DriveBuddy", "TTS requested: ${LogText.shown(text, quiet)}, ready=$ready")

        if (!ready) {
            if (canRebuild) {
                Log.i("DriveBuddy", "TTS: engine is still starting, will speak as soon as it is ready")
                afterRebuild = { attempt(text, onStart, onDone, onProgress, quiet, canRebuild = false) }
                giveUp = { onDone() }
                return
            }
            Log.e("DriveBuddy", "TTS unavailable; skipping speech")
            onDone()
            return
        }
        val id = UUID.randomUUID().toString()
        currentId = id
        startCallback = onStart
        doneCallback = onDone
        progressCallback = onProgress
        progressSeen = false
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        Log.i("DriveBuddy", "TTS speak result=$result, id=$id")
        if (result == TextToSpeech.ERROR) {
            if (canRebuild) {
                rebuildEngine(
                    retry = { attempt(text, onStart, onDone, onProgress, quiet, canRebuild = false) },
                    giveUp = { finish() },
                )
            } else {
                Log.e("DriveBuddy", "TTS speak() rejected utterance again after restarting the engine")
                finish()
            }
        }
    }

    /** The engine connection went stale: reconnect, then run [retry]. If it won't start, run [giveUp]. */
    private fun rebuildEngine(retry: () -> Unit, giveUp: () -> Unit) {
        Log.w("DriveBuddy", "TTS: the speech engine stopped answering, reconnecting to it")
        ready = false
        afterRebuild = retry
        this.giveUp = giveUp
        try { tts.shutdown() } catch (e: Exception) { Log.w("DriveBuddy", "TTS: old engine would not shut down: ${e.javaClass.simpleName}") }
        tts = TextToSpeech(appContext, this)
    }

    private fun finish() {
        if (progressCallback != null && !progressSeen) {
            Log.w("DriveBuddy", "TTS: this engine sent no progress callbacks, the card cannot follow the voice")
        }
        progressCallback = null
        val done = doneCallback
        doneCallback = null
        startCallback = null
        currentId = null
        done?.invoke()
    }

    /** Cuts off speech immediately and runs the normal "done" path. */
    fun stop() {
        afterRebuild = null
        giveUp = null
        tts.stop()
        finish()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        ready = false
    }
}
