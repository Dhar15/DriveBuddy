package app.drivebuddy.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.drivebuddy.R

/** The short spoken "mhm?" after the wake word. The clip is always loaded and waiting, so play() starts instantly. */
class AckVoice(context: Context) {

    companion object {
        private const val TAG = "DriveBuddy"
        private const val MAX_WAIT_MS = 2000L
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var next: MediaPlayer? = null
    private var ready = false
    private var released = false

    init { prepareNext() }

    /** Loads the clip into a fresh player and leaves it waiting. */
    private fun prepareNext() {
        if (released || next != null) return
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            app.resources.openRawResourceFd(R.raw.ack).use {
                player.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            player.setOnPreparedListener {
                ready = true
                Log.d(TAG, "ack: clip ready")
            }
            player.setOnErrorListener { mp, what, extra ->
                Log.w(TAG, "ack: player error while preparing ($what/$extra)")
                if (next === mp) { next = null; ready = false }
                mp.release()
                true
            }
            next = player
            ready = false
            player.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "ack: could not load the clip: ${e.message}")
            player.release()
            next = null
        }
    }

    /** Plays the waiting clip, then calls [onDone] exactly once. If it isn't ready, calls [onDone] at once. */
    fun play(onDone: () -> Unit) {
        val player = next
        if (player == null || !ready || released) {
            Log.i(TAG, "ack: clip not ready, skipping")
            onDone()
            prepareNext()
            return
        }
        next = null
        ready = false
        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            try { player.release() } catch (e: Exception) { /* already released */ }
            onDone()
            prepareNext()
        }
        player.setOnCompletionListener { finish() }
        player.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "ack: player error while playing ($what/$extra)")
            finish()
            true
        }
        Log.i(TAG, "ack: playing")
        player.start()
        main.postDelayed({ finish() }, MAX_WAIT_MS) // safety net: never leave the driver waiting
    }

    fun release() {
        released = true
        main.removeCallbacksAndMessages(null)
        next?.release()
        next = null
    }
}