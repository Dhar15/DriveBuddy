package app.drivebuddy.audio

import android.media.AudioManager
import android.media.ToneGenerator

/** Short non-speech sounds so the driver always knows the app heard them. */
class Cue {
    private var tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    } catch (e: RuntimeException) {
        null
    }

    /** "I'm listening." */
    fun listening() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
    }

    /** "Got it" - played the moment you stop speaking, before any processing. */
    fun ack() {
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 100)
    }

    fun release() {
        tone?.release()
        tone = null
    }
}
