package app.drivebuddy.features.learning

/** What the buddy says for the word and the GK of the day. Pure, so it is unit tested. */
object LearningText {

    fun word(word: String, meaning: String, example: String): String {
        val parts = mutableListOf("Word of the day: ${word.trim()}.")
        if (meaning.isNotBlank()) parts += "It means ${sentence(meaning)}"
        if (example.isNotBlank()) parts += "For example: ${sentence(example)}"
        return parts.joinToString(" ")
    }

    fun gk(category: String, fact: String): String {
        val topic = category.trim().lowercase()
        val lead = if (topic.isEmpty() || topic == "miscellaneous") "Your G K of the day:"
        else "Your G K of the day, from $topic:"
        return "$lead ${sentence(fact)}"
    }

    /** Trimmed, and ending in a full stop so the voice pauses properly. */
    private fun sentence(text: String): String {
        val t = text.trim()
        return if (t.last() in ".!?") t else "$t."
    }
}