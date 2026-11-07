package app.drivebuddy.core

/** Understands "five", "number 5", "open story three", "more", "done" for a list of up to [count] stories. */
object StoryChoice {
    private val numbers = mapOf(
        "1" to 1, "one" to 1, "won" to 1, "first" to 1,
        "2" to 2, "two" to 2, "to" to 2, "too" to 2, "tu" to 2, "second" to 2,
        "3" to 3, "three" to 3, "tree" to 3, "third" to 3,
        "4" to 4, "four" to 4, "for" to 4, "fourth" to 4,
        "5" to 5, "five" to 5, "fifth" to 5,
    )
    private val filler = setOf(
        "number", "story", "open", "read", "the", "option", "item", "please", "pick", "choose", "select", "show", "me",
    )
    private val more = setOf("more", "next", "next ones", "next five", "more stories")
    private val stop = setOf("cancel", "done", "stop", "no", "nothing", "never mind", "nevermind", "exit", "that's all")
    private val nonWord = Regex("[^a-z0-9' ]")

    fun parse(raw: String, count: Int): PlaceChoice {
        val words = raw.lowercase().replace(nonWord, " ").split(" ").filter { it.isNotEmpty() && it !in filler }
        val phrase = words.joinToString(" ")
        if (phrase in stop) return PlaceChoice.Cancel
        if (phrase in more) return PlaceChoice.More
        val n = numbers[phrase] ?: return PlaceChoice.Unknown
        return if (n in 1..count) PlaceChoice.Pick(n) else PlaceChoice.Unknown
    }
}