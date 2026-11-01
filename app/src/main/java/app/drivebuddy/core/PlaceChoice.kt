package app.drivebuddy.core

/** How the driver answers the list of nearby places. Pure logic, no Android. */
sealed class PlaceChoice {
    data class Pick(val number: Int) : PlaceChoice()
    object More : PlaceChoice()
    object Cancel : PlaceChoice()
    object Unknown : PlaceChoice()

    companion object {
        private val nonWord = Regex("[^a-z0-9' ]")
        private val spaces = Regex("\\s+")
        private val cancelWords = setOf("cancel", "stop", "quit", "exit", "forget", "nevermind", "abort")
        private val cancelPhrases = listOf("never mind", "no thanks", "no thank you", "not now", "leave it")
        private val moreWords = setOf(
            "more", "next", "another", "other", "others", "else", "different",
            "none", "neither", "no", "nope", "nah", "not",
        )
        private val numbers = mapOf(
            "1" to 1, "one" to 1, "won" to 1, "first" to 1, "closest" to 1, "nearest" to 1,
            "2" to 2, "two" to 2, "tu" to 2, "second" to 2,
            "3" to 3, "three" to 3, "tree" to 3, "third" to 3,
        )

        /** [count] is how many places are on offer (1 to 3). Cancel beats more, more beats a number. */
        fun parse(raw: String, count: Int): PlaceChoice {
            val text = raw.lowercase().replace(nonWord, " ").replace(spaces, " ").trim()
            if (text.isEmpty()) return Unknown
            val words = text.split(" ")
            if (words.any { it in cancelWords } || cancelPhrases.any { text.contains(it) }) return Cancel
            if (words.any { it in moreWords } || text.contains("show more")) return More
            val picked = if ("last" in words) count else words.firstNotNullOfOrNull { numbers[it] }
            if (picked != null && picked in 1..count) return Pick(picked)
            if ((text == "to" || text == "too") && count >= 2) return Pick(2) // "two" often arrives as "to"
            return Unknown
        }
    }
}