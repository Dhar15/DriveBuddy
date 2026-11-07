package app.drivebuddy.core

/** One line of a conversation. [role] is "system", "user" or "assistant". */
data class ChatTurn(val role: String, val content: String)

/**
 * The memory and manners of the chat buddy. Pure logic, no Android.
 * Keeps only the last few messages, counts exchanges so a chat cannot run on forever,
 * and cleans replies so they sound right when spoken.
 */
class BuddyChat(private val maxMessages: Int = 6, private val maxExchanges: Int = 5) {

    private val history = ArrayDeque<ChatTurn>()

    /** How many replies the buddy has given in this conversation. */
    var exchanges = 0
        private set

    val canContinue: Boolean get() = exchanges < maxExchanges

    fun addUser(text: String) {
        history.addLast(ChatTurn("user", text.trim()))
        trim()
    }

    fun addAssistant(text: String) {
        history.addLast(ChatTurn("assistant", text.trim()))
        exchanges++
        trim()
    }

    /** What to send to the model: the system prompt, then the recent conversation. */
    fun messages(): List<ChatTurn> = listOf(ChatTurn("system", SYSTEM_PROMPT)) + history

    fun reset() {
        history.clear()
        exchanges = 0
    }

    private fun trim() {
        while (history.size > maxMessages) history.removeFirst()
        while (history.isNotEmpty() && history.first().role != "user") history.removeFirst() // always start on the driver's turn
    }

    companion object {
        const val SYSTEM_PROMPT =
            "You are Oreo, a friendly voice companion riding along with the driver of a car. " +
                    "Everything you say is spoken aloud, so: " +
                    "reply in one or two short, plain sentences, with no lists, markdown, emojis or special symbols. " +
                    "Sound like a relaxed, warm friend; light humour is welcome and preaching is not. " +
                    "The driver's eyes and hands are busy, so never ask them to look at or tap anything, and ask at most one short question. " +
                    "You cannot take actions: you cannot play music, navigate, call, text, set timers or alarms, open apps or change phone settings, " +
                    "and you cannot see the phone, the location, the weather or the traffic. Never claim you did any of these. " +
                    "If the driver asks for one, tell them what to say, for example: Try saying, set a timer for ten minutes. " +
                    "Do not invent facts; if you are not sure, say so briefly. " +
                    "Take every sentence as meant. Only if a sentence is truly unintelligible, say you did not quite catch it; never say that for a clear question. " +
                    "When you cannot answer because you cannot look something up, say so in one friendly sentence and offer something useful instead; never tell the driver to ask someone else. " +                    "if it makes no sense, say you did not quite catch it and ask them to say it again. " +
                    "If the driver sounds drowsy, upset or distracted, gently suggest a short break when it is safe."

        private const val MAX_SPOKEN_CHARS = 350

        private val goodbyes = listOf(
            "thanks", "thank you", "thanks a lot", "that's all", "that is all", "that's it", "that is it",
            "bye", "goodbye", "never mind", "nevermind", "no thanks", "i'm good", "i am good", "okay thanks", "ok thanks",
        )

        /** True when the driver is ending the chat ("thanks", "that's all", "bye"). Only short sentences count. */
        fun isGoodbye(text: String): Boolean {
            val clean = text.lowercase().replace(Regex("[^a-z' ]"), " ").trim().replace(Regex(" +"), " ")
            if (clean.isEmpty() || clean.split(" ").size > 5) return false
            return goodbyes.any { clean == it || clean.startsWith("$it ") || clean.endsWith(" $it") }
        }

        /** Makes a model reply fit for speech: no markdown or emoji, one line, and not too long. */
        fun tidy(reply: String): String {
            val noSymbols = buildString {
                reply.codePoints().forEach { cp ->
                    val emoji = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp == 0xFE0F
                    if (!emoji && cp != '*'.code && cp != '#'.code && cp != '`'.code && cp != '_'.code) appendCodePoint(cp)
                }
            }
            val oneLine = noSymbols
                .replace(Regex("(?m)^\\s*[-•]\\s+"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (oneLine.length <= MAX_SPOKEN_CHARS) return oneLine
            val cut = oneLine.take(MAX_SPOKEN_CHARS)
            val end = maxOf(cut.lastIndexOf('.'), cut.lastIndexOf('!'), cut.lastIndexOf('?'))
            return if (end > 40) cut.take(end + 1) else cut.trimEnd()
        }
    }
}