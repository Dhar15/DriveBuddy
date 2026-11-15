package app.drivebuddy.audio

import android.content.Context
import android.media.AudioManager
import android.util.Log

/** Makes sure the assistant's voice is audible even when the phone's volume is low, then gives the volume back. */
class AssistantVolume(context: Context) {

    companion object {
        private const val TAG = "DriveBuddy"
        const val FLOOR_PERCENT = 60

        /** The level to raise to, or null when the current level is already high enough. */
        fun raisedLevel(current: Int, max: Int, floorPercent: Int = FLOOR_PERCENT): Int? {
            val floor = (max * floorPercent + 99) / 100
            return if (current >= floor) null else floor
        }
    }

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var original: Int? = null
    private var raisedTo = 0

    fun raise() {
        if (original != null) return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = raisedLevel(current, max) ?: return
        original = current
        val actual = moveTo(target)
        raisedTo = actual // where the phone really ended up, which can overshoot in steps
        Log.i(
            TAG,
            "assistant volume: asked for $target (was $current, max $max), phone reports $actual" +
                    if (actual < target) " <- the phone did not reach the wanted level" else "",
        )
    }

    /** Puts the volume back, unless you changed it yourself in the meantime. */
    fun restore() {
        val before = original ?: return
        original = null
        val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (now == raisedTo) {
//            audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
            moveTo(before)
            Log.i(TAG, "assistant volume: restored to $before")
        } else {
            Log.i(TAG, "assistant volume: left at $now, it was changed during the interaction")
        }
    }

    /** Moves the music volume to [level]. Tries a direct jump first, then volume-key-style steps. Returns the level reached. */
    private fun moveTo(level: Int): Int {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
        var now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (now == level) return now
        val raising = now < level
        var steps = 0
        while ((raising && now < level) || (!raising && now > level)) {
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (raising) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                0,
            )
            val after = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            steps++
            if (after == now || steps >= 200) break // no progress: the phone is ignoring us
            now = after
        }
        Log.i(TAG, "assistant volume: direct jump refused, key-style steps: $steps, reached $now of wanted $level")
        return now
    }
}