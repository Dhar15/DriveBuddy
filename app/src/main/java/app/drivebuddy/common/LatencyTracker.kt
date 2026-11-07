package app.drivebuddy.common

import kotlin.text.iterator

/** Points in one voice interaction that we time. */
enum class Mark { TRIGGER, LISTENING, SPEECH_END, ACK_CUE, STT_FINAL, ROUTED, TTS_START, TTS_DONE }

/**
 * Records when each [Mark] happened during one interaction and renders one JSON line.
 * Pure Kotlin (no Android classes) so it can be unit-tested on the laptop.
 */
class LatencyTracker(
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val marks = LinkedHashMap<Mark, Long>()
    private var startedAtWallMs: Long = 0L

    var sttEngine: String = ""
    var transcript: String = ""
    var command: String = ""

    fun reset() {
        marks.clear()
        sttEngine = ""
        transcript = ""
        command = ""
        startedAtWallMs = 0L
    }

    /** Records [mark] once; later calls for the same mark are ignored. */
    fun mark(mark: Mark) {
        if (marks.containsKey(mark)) return
        if (marks.isEmpty()) startedAtWallMs = wallClockMs()
        marks[mark] = monotonicMs()
    }

    fun has(mark: Mark): Boolean = marks.containsKey(mark)

    /** Milliseconds from [from] to [to], or null if either was not reached. */
    fun elapsed(from: Mark, to: Mark): Long? {
        val a = marks[from] ?: return null
        val b = marks[to] ?: return null
        return b - a
    }

    fun toJson(): String {
        val first = marks.values.firstOrNull() ?: 0L
        val markJson = marks.entries.joinToString(",") { "\"${it.key.name}\":${it.value - first}" }
        val derived = listOf(
            "trigger_to_listening" to elapsed(Mark.TRIGGER, Mark.LISTENING),
            "end_to_ack" to elapsed(Mark.SPEECH_END, Mark.ACK_CUE),
            "end_to_final" to elapsed(Mark.SPEECH_END, Mark.STT_FINAL),
            "final_to_voice" to elapsed(Mark.STT_FINAL, Mark.TTS_START),
            "end_to_voice" to elapsed(Mark.SPEECH_END, Mark.TTS_START),
        ).joinToString(",") { "\"${it.first}\":${it.second ?: "null"}" }
        return "{\"ts\":$startedAtWallMs," +
            "\"stt\":\"${escape(sttEngine)}\"," +
            "\"transcript\":\"${escape(transcript)}\"," +
            "\"command\":\"${escape(command)}\"," +
            "\"marks_ms\":{$markJson}," +
            "$derived}"
    }

    private fun escape(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }
}
