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

/** Thin wrapper over Android's built-in TextToSpeech. Callbacks are delivered on the main thread. */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var currentId: String? = null
    private var startCallback: (() -> Unit)? = null
    private var doneCallback: (() -> Unit)? = null

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e("DriveBuddy", "TTS initialization failed: status=$status")
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
    }

    /** Speaks [text]. [onStart] fires when audio actually begins; [onDone] when it ends or fails. */
    fun speak(text: String, onStart: () -> Unit, onDone: () -> Unit) {
        Log.i("DriveBuddy", "TTS requested: '$text', ready=$ready")

        if (!ready) {
            Log.e("DriveBuddy", "TTS unavailable; skipping speech")
            onDone()
            return
        }
        val id = UUID.randomUUID().toString()
        currentId = id
        startCallback = onStart
        doneCallback = onDone
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        Log.i("DriveBuddy", "TTS speak result=$result, id=$id")
        if (result == TextToSpeech.ERROR) {
            Log.e("DriveBuddy", "TTS speak() rejected utterance")
            finish()
        }
    }

    private fun finish() {
        val done = doneCallback
        doneCallback = null
        startCallback = null
        currentId = null
        done?.invoke()
    }

    /** Cuts off speech immediately and runs the normal "done" path. */
    fun stop() {
        tts.stop()
        finish()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        ready = false
    }
}
