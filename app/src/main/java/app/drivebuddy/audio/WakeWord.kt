package app.drivebuddy.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.abs

/**
 * Always-on "Hi Oreo" detector. A small neural network trained for exactly this phrase (openWakeWord) listens to
 * the microphone on the phone. Nothing is recorded, saved or sent anywhere.
 *
 * It holds the microphone only while [start]ed. The service calls [stop] before the speech recogniser
 * needs the mic, and [start] again when the interaction is over.
 */
class WakeWord(
    private val context: Context,
    private val onProblem: (String) -> Unit,
    private val onWake: () -> Unit,
) {

    companion object {
        /** 0..1. Lower = wakes more easily but also by mistake. Tune with the "wake score" lines in Logcat. */
        const val THRESHOLD = 0.7f
        /** Frames (80 ms each) at or above THRESHOLD needed before waking. One lone frame is usually a false alarm. */
        private const val PATIENCE = 2
        /** One frame at least this strong wakes on its own. Every real "Hi Oreo" so far peaked at 0.85 or more; noise stayed under 0.8. */
        private const val STRONG = 0.85f
        /** A frame scoring below this restarts the count. */
        private const val RESET_BELOW = 0.3f
        /** The loudest sample in the last second must reach this, so near-silence can never wake us. */
        private const val MIN_LOUDNESS = 1200
        private const val LOUD_WINDOW = 12 // chunks, about one second
        /** Scores at or above this are written to Logcat, so we can see how close near-misses were. */
        private const val LOG_FROM = 0.2f
    }

    private val tag = "DriveBuddy"
    private val sampleRate = 16_000
    private var detector: OpenWakeWord? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    @Synchronized
    fun start() {
        if (running) return
        running = true
        worker = Thread({ listen() }, "wake-word").also { it.start() }
    }

    /** Stops listening and waits (briefly) until the microphone is free again. */
    @Synchronized
    fun stop() {
        running = false
        worker?.join(1_000)
        worker = null
    }

    fun release() {
        stop()
        detector?.close()
        detector = null
    }

    private fun listen() {
        val oww = try {
            detector ?: createDetector().also { detector = it }
        } catch (e: Throwable) {
            Log.e(tag, "wake word model failed to load: ${e.message}")
            onProblem("model failed to load (${e.message})")
            running = false
            return
        }
        oww.reset() // never react to audio heard before this listening session

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, sampleRate), // about one second of audio
            )
        } catch (e: Exception) {
            Log.w(tag, "wake word microphone failed: ${e.message}")
            onProblem("microphone failed (${e.message})")
            running = false
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(tag, "wake word microphone not initialised")
            onProblem("microphone not available (is another app using it?)")
            record.release()
            running = false
            return
        }

        var detected = false
        try {
            record.startRecording()
            Log.i(tag, "wake word listening")
            val chunk = ShortArray(OpenWakeWord.CHUNK) // 80 ms per step
            var filled = 0
            var peak = 0          // loudest sample since the last heartbeat
            var steps = 0
            var silentSteps = 0   // consecutive chunks that were exactly silent
            var reportedSilence = false
            var best = 0f         // highest score since the last heartbeat
            var hits = 0          // frames in a row at or above THRESHOLD
            val recentPeaks = IntArray(LOUD_WINDOW)
            var ringPos = 0

            while (running) {
                val n = record.read(chunk, filled, chunk.size - filled)
                if (n < 0) {
                    Log.w(tag, "wake word read error $n")
                    onProblem("microphone read error $n")
                    break
                }
                filled += n
                if (filled < chunk.size) continue
                filled = 0

                var chunkPeak = 0
                for (s in chunk) chunkPeak = maxOf(chunkPeak, abs(s.toInt()))
                peak = maxOf(peak, chunkPeak)
                recentPeaks[ringPos] = chunkPeak
                ringPos = (ringPos + 1) % LOUD_WINDOW
                silentSteps = if (chunkPeak == 0) silentSteps + 1 else 0
                if (silentSteps >= 100 && !reportedSilence) { // 8 s of digital silence: the mic is being blocked
                    reportedSilence = true
                    onProblem("microphone returns silence (another app may hold it)")
                }

                val score = oww.score(chunk)
                best = maxOf(best, score)
                if (score >= LOG_FROM) Log.d(tag, "wake score %.2f".format(score))
                if (++steps % 62 == 0) { // about every 5 s
                    Log.i(tag, "wake word alive: mic peak=$peak of 32767, best score %.2f".format(best))
                    peak = 0
                    best = 0f
                }
                if (score >= THRESHOLD) {
                    hits++
                    if (hits >= PATIENCE || score >= STRONG) {
                        val loudness = recentPeaks.max()
                        if (loudness >= MIN_LOUDNESS) {
                            Log.i(tag, "wake word detected, score ${"%.2f".format(score)} after $hits frames, loudness $loudness")
                            detected = true
                            break
                        }
                        Log.i(tag, "wake word ignored: too quiet (loudness $loudness, score ${"%.2f".format(score)})")
                        hits = 0
                    }
                } else if (score < RESET_BELOW) {
                    hits = 0
                }
            }
        } finally {
            running = false
            try {
                record.stop()
            } catch (e: IllegalStateException) {
            }
            record.release()
        }
        if (detected) onWake()
    }

    private fun createDetector(): OpenWakeWord {
        fun asset(name: String) = context.assets.open("wakeword/$name").use { it.readBytes() }
        return OpenWakeWord(
            melModel = asset("melspectrogram.onnx"),
            embeddingModel = asset("embedding_model.onnx"),
            wakeModel = asset("hi_oreo.onnx"),
        )
    }
}