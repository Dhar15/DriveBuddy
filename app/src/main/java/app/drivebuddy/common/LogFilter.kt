package app.drivebuddy.common

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
    fun apply(lines: List<LogLine>, q: LogQuery, maxRows: Int = 5000): LogResult {
        val needle = q.text.trim().lowercase()
        val minIdx = LEVELS.indexOf(q.minLevel)
        val kept = ArrayList<LogLine>()
        for (l in lines) {
            if (l.isBreak) { kept += l; continue }
            if (LEVELS.indexOf(l.level) < minIdx) continue
            if (q.appOnly && l.tag !in APP_TAGS) continue
            if (needle.isNotEmpty() && !l.raw.lowercase().contains(needle)) continue
            if (needle.isEmpty() && l.tag == "DriveBuddy" && l.message.startsWith("triggered by")) {
                kept += LogLine.divider()
            }
            kept += l
        }
        val total = kept.count { !it.isBreak }
        val tail = if (kept.size > maxRows) kept.takeLast(maxRows) else kept
        return LogResult(if (q.newestFirst) tail.asReversed() else tail, total)
    }
}