package app.drivebuddy.core

/** Turns RSS headline titles into speakable text. Pure logic. */
object NewsText {
    const val BATCH = 5

    /** Removes tags, extra spaces and a trailing " | Publisher". */
    fun clean(raw: String): String =
        raw.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().substringBefore(" | ").trim()

    fun speak(batch: List<String>, first: Boolean, source: String): String {
        val lead = if (first) "Here are the latest headlines from $source. " else ""
        return lead + batch.withIndex().joinToString(" ") { (i, h) -> "Number ${i + 1}. ${sentence(h)}" }
    }

    /** A tile-sized headline: cut at a word boundary with an ellipsis. */
    fun tile(title: String, max: Int = 70): String =
        if (title.length <= max) title
        else title.take(max).substringBeforeLast(' ').trimEnd(',', ':', ';', '-', ' ') + "…"

    private fun sentence(headline: String): String =
        if (headline.isEmpty() || headline.last() in ".!?") headline else "$headline."
}