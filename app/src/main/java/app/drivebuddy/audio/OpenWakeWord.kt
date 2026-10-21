package app.drivebuddy.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.Random

/**
 * The openWakeWord pipeline, step for step (verified against the reference Python implementation):
 *   80 ms of audio -> mel spectrogram (8 frames) -> speech embedding (96 numbers) -> your wake-word model.
 * Each call to [score] takes exactly [CHUNK] samples and returns how likely it is that the wake word was just said (0..1).
 *
 * Not thread-safe: use it from one thread (the wake-word thread).
 */
class OpenWakeWord(melModel: ByteArray, embeddingModel: ByteArray, wakeModel: ByteArray) {

    companion object {
        const val CHUNK = 1280            // 80 ms at 16 kHz
        private const val MEL_BINS = 32
        private const val EMB_WINDOW = 76 // mel frames per embedding
        private const val EMB_DIM = 96
        private const val WAKE_FRAMES = 16 // embeddings the wake model looks at (~1.3 s)
        private const val RAW_KEEP = CHUNK + 160 * 3
    }

    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
    }
    private val mel = env.createSession(melModel, options)
    private val emb = env.createSession(embeddingModel, options)
    private val wake = env.createSession(wakeModel, options)
    private val melIn = mel.inputNames.first()
    private val embIn = emb.inputNames.first()
    private val wakeIn = wake.inputNames.first()

    private val raw = FloatArray(RAW_KEEP)
    private var rawLen = 0
    private val melBuf = FloatArray(EMB_WINDOW * MEL_BINS)
    private val feats = FloatArray(WAKE_FRAMES * EMB_DIM)
    private val startFeats: FloatArray = noiseEmbeddings()

    init {
        reset()
    }

    /** Forget all audio heard so far (call before listening again, so old audio cannot trigger it). */
    fun reset() {
        rawLen = 0
        melBuf.fill(1f)
        startFeats.copyInto(feats)
    }

    fun score(chunk: ShortArray): Float {
        require(chunk.size == CHUNK) { "need exactly $CHUNK samples" }
        // keep the last CHUNK + 480 samples (mel frames need a little history)
        val keep = minOf(rawLen, RAW_KEEP - CHUNK)
        raw.copyInto(raw, 0, rawLen - keep, rawLen)
        for (i in 0 until CHUNK) raw[keep + i] = chunk[i].toFloat()
        rawLen = keep + CHUNK

        val frames = melFrames(raw, 0, rawLen)          // about 8 new frames
        val n = frames.size / MEL_BINS
        val total = EMB_WINDOW * MEL_BINS
        if (n >= EMB_WINDOW) {
            frames.copyInto(melBuf, 0, frames.size - total, frames.size)
        } else {
            melBuf.copyInto(melBuf, 0, n * MEL_BINS, total)
            frames.copyInto(melBuf, total - n * MEL_BINS)
        }
        pushEmbedding(embed(melBuf))
        return run(wake, wakeIn, feats, longArrayOf(1, WAKE_FRAMES.toLong(), EMB_DIM.toLong()))[0]
    }

    fun close() {
        mel.close(); emb.close(); wake.close(); options.close()
    }

    private fun pushEmbedding(e: FloatArray) {
        feats.copyInto(feats, 0, EMB_DIM, feats.size)
        e.copyInto(feats, feats.size - EMB_DIM)
    }

    /** Mel frames for [len] samples, already scaled the way the embedding model expects. */
    private fun melFrames(samples: FloatArray, from: Int, len: Int): FloatArray {
        val data = samples.copyOfRange(from, from + len)
        val out = run(mel, melIn, data, longArrayOf(1, len.toLong()))
        for (i in out.indices) out[i] = out[i] / 10f + 2f
        return out
    }

    private fun embed(window: FloatArray): FloatArray =
        run(emb, embIn, window, longArrayOf(1, EMB_WINDOW.toLong(), MEL_BINS.toLong(), 1))

    private fun run(session: OrtSession, inputName: String, data: FloatArray, shape: LongArray): FloatArray {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                val buf = (result[0] as OnnxTensor).floatBuffer
                return FloatArray(buf.remaining()).also { buf.get(it) }
            }
        }
    }

    /** Embeddings of 4 s of faint noise: what the wake model sees "before" any real audio, as in openWakeWord. */
    private fun noiseEmbeddings(): FloatArray {
        val rnd = Random(0)
        val noise = FloatArray(16_000 * 4) { (rnd.nextInt(2000) - 1000).toFloat() }
        val m = melFrames(noise, 0, noise.size)
        val frames = m.size / MEL_BINS
        val all = ArrayList<FloatArray>()
        var start = 0
        while (start + EMB_WINDOW <= frames) {
            all.add(embed(m.copyOfRange(start * MEL_BINS, (start + EMB_WINDOW) * MEL_BINS)))
            start += 8
        }
        val out = FloatArray(WAKE_FRAMES * EMB_DIM)
        val last = all.takeLast(WAKE_FRAMES)
        for ((i, e) in last.withIndex()) e.copyInto(out, i * EMB_DIM)
        return out
    }
}