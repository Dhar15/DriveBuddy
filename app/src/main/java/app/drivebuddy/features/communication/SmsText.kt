package app.drivebuddy.features.communication

/** Cleans up dictated text before it is sent: the recogniser output is often lower case. */
object SmsText {
    private val loneI = Regex("\\bi\\b")

    fun tidy(spoken: String): String {
        val t = spoken.trim().replace(Regex("\\s+"), " ").replace(loneI, "I")
        return t.replaceFirstChar { it.uppercase() }
    }
}