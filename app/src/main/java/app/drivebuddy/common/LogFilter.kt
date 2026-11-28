package app.drivebuddy.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One parsed logcat line. [isBreak] rows are separators (new session / new interaction). */
data class LogLine(
    val raw: String,
    val time: String,
    val level: Char,
    val tag: String,
    val message: String,
    val isBreak: Boolean = false,
) {
    companion object {
        const val SESSION_MARK = "=========="
        fun divider() = LogLine("----------  new interaction  ----------", "", 'I', "", "", true)
    }
}

data class LogQuery(
    val minLevel: Char = 'V',
    val appOnly: Boolean = true,
    val text: String = "",
    val newestFirst: Boolean = false,
    val windowMs: Long? = null, // null = all time; otherwise only lines from the last windowMs
    val fromMs: Long? = null, // start of an explicit range (inclusive)
    val toMs: Long? = null,   // end of an explicit range (inclusive)
)

data class LogResult(val rows: List<LogLine>, val total: Int)

object LogParser {
    // threadtime: "11-24 20:23:10.034  25012  25012 I DriveBuddy: message"
    private val re = Regex("""^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+\d+\s+\d+\s+([VDIWEF])\s+(.+?)\s*: (.*)$""")

    fun parse(raw: String): LogLine? {
        if (raw.startsWith(LogLine.SESSION_MARK)) return LogLine(raw, "", 'I', "", raw, true)
        val m = re.find(raw) ?: return null
        return LogLine(raw, m.groupValues[1], m.groupValues[2][0], m.groupValues[3], m.groupValues[4])
    }
}

object LogFilter {
    private const val LEVELS = "VDIWEF"
    private val APP_TAGS = setOf("DriveBuddy", "AndroidRuntime") // AndroidRuntime = crash stack traces

    /** Filters, adds a divider before each interaction, keeps the newest [maxRows], then sorts. */
    fun apply(lines: List<LogLine>, q: LogQuery, maxRows: Int = 5000, nowMs: Long = System.currentTimeMillis()): LogResult {        val needle = q.text.trim().lowercase()
        val minIdx = LEVELS.indexOf(q.minLevel)
        // Log lines carry "MM-dd HH:mm:ss.SSS" (no year), which compares correctly as text.
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
        val lo = q.fromMs ?: q.windowMs?.let { nowMs - it }
        val hi = q.toMs ?: if (q.windowMs != null) nowMs else null
        val loStr = lo?.let { fmt.format(Date(it)) }
        val hiStr = hi?.let { fmt.format(Date(it)) }
        val kept = ArrayList<LogLine>()
        for (l in lines) {
            if (l.isBreak) { kept += l; continue }
            if (!inRange(l.time, loStr, hiStr)) continue
            if (LEVELS.indexOf(l.level) < minIdx) continue
            if (q.appOnly && l.tag !in APP_TAGS) continue
            if (needle.isNotEmpty() && !l.raw.lowercase().contains(needle)) continue
            val startsInteraction = l.tag == "DriveBuddy" &&
                    (l.message.startsWith("wake word detected") ||
                            (l.message.startsWith("triggered by") && !l.message.contains("wake word")))
            if (needle.isEmpty() && startsInteraction) {
                kept += LogLine.divider()
            }
            kept += l
        }
        val pruned = pruneEmptyBreaks(kept)
        val total = pruned.count { !it.isBreak }
        val tail = if (pruned.size > maxRows) pruned.takeLast(maxRows) else pruned
        return LogResult(if (q.newestFirst) tail.asReversed() else tail, total)
    }

    /** Lines carry "MM-dd HH:mm:ss.SSS" (no year), which compares correctly as text. lo > hi means the range crosses New Year. */
    private fun inRange(t: String, lo: String?, hi: String?): Boolean {
        if (lo != null && hi != null && lo > hi) return t >= lo || t <= hi
        return (lo == null || t >= lo) && (hi == null || t <= hi)
    }

    /** Drops separators with no lines under them (e.g. a session with no errors when "Errors only"). */
    private fun pruneEmptyBreaks(rows: List<LogLine>): List<LogLine> {
        val out = ArrayList<LogLine>()
        var anyData = false // any line since the next session banner
        var segData = false // any line since the next separator of any kind
        for (r in rows.asReversed()) {
            when {
                !r.isBreak -> { anyData = true; segData = true; out += r }
                r.raw.startsWith(LogLine.SESSION_MARK) -> { if (anyData) out += r; anyData = false; segData = false }
                else -> { if (segData) out += r; segData = false }
            }
        }
        return out.asReversed()
    }
}