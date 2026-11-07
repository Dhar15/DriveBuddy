package app.drivebuddy.command

/** Reads a spoken answer to a yes/no question. */
object YesNo {
    private val yes = setOf(
        "yes", "yeah", "yep", "yup", "yea", "sure", "ok", "okay", "correct", "confirm", "confirmed", "affirmative",
        "absolutely", "definitely", "please", "go", "send", "do", "right",
    )
    private val no = setOf(
        "no", "nope", "nah", "cancel", "stop", "negative", "dont", "don't", "not", "never", "wait", "abort", "wrong",
    )
    private val nonWord = Regex("[^a-z' ]")

    /** true = yes, false = no, null = couldn't tell. A "no" word anywhere wins, so "yes don't send it" is a no. */
    fun parse(text: String): Boolean? {
        val words = text.lowercase().replace(nonWord, " ").split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        if (words.any { it in no }) return false
        if (words.any { it in yes }) return true
        return null
    }
}