package app.drivebuddy.core

enum class MediaAction { PLAY, PAUSE, STOP, NEXT, PREVIOUS }

/** What the router decided the user wants. */
sealed class Command {
    object Empty : Command()
    object Stop : Command()
    data class Call(val name: String) : Command()
    data class Navigate(val destination: String) : Command()
    data class Media(val action: MediaAction) : Command()
    data class Play(val query: String, val target: String?) : Command()
    data class Echo(val text: String) : Command()
}

/** Rule-based fast lane: no model, no network. */
object CommandParser {
    private val stopPhrases = setOf("cancel", "never mind", "nevermind", "forget it")

    private val mediaPhrases = mapOf(
        "stop" to MediaAction.STOP,
        "pause" to MediaAction.PAUSE,
        "pause music" to MediaAction.PAUSE,
        "pause the music" to MediaAction.PAUSE,
        "stop music" to MediaAction.STOP,
        "stop the music" to MediaAction.STOP,
        "play" to MediaAction.PLAY,
        "play music" to MediaAction.PLAY,
        "play the music" to MediaAction.PLAY,
        "resume" to MediaAction.PLAY,
        "resume music" to MediaAction.PLAY,
        "resume the music" to MediaAction.PLAY,
        "next" to MediaAction.NEXT,
        "next song" to MediaAction.NEXT,
        "next track" to MediaAction.NEXT,
        "skip" to MediaAction.NEXT,
        "skip song" to MediaAction.NEXT,
        "previous" to MediaAction.PREVIOUS,
        "previous song" to MediaAction.PREVIOUS,
        "previous track" to MediaAction.PREVIOUS,
        "go back" to MediaAction.PREVIOUS,
    )

    private val nonWord = Regex("[^a-z0-9' ]")
    private val spaces = Regex("\\s+")
    // "hey oreo, can you please ..." -> "..." (every part is optional)
    private val fillerPrefix = Regex("^(?:(?:hey|hi|ok|okay) oreo )?(?:(?:can|could|would|will) you )?(?:please )?")
    private val fillerSuffix = Regex(" please$")
    private val callRule = Regex("^(?:call|phone|ring|dial) (.+)$")
    private val navigateRule =
        Regex("^(?:navigate to|navigate me to|take me to|take me|directions to|drive to|drive me to|go to) (.+)$")
    private val article = Regex("^(?:the|my) ")
    private val playRule = Regex("^play (.+?)(?: on (spotify|youtube music))?$")

    fun parse(raw: String): Command {
        val normalized = raw.lowercase().replace(nonWord, " ").replace(spaces, " ").trim()
        if (normalized.isEmpty()) return Command.Empty

        val core = normalized.replace(fillerPrefix, "").replace(fillerSuffix, "").trim()
        if (core.isEmpty()) return Command.Empty
        if (core in stopPhrases) return Command.Stop

        mediaPhrases[core]?.let { return Command.Media(it) }

        playRule.find(core)?.let {
            return Command.Play(it.groupValues[1].trim(), it.groupValues[2].ifEmpty { null })
        }

        callRule.find(core)?.let {
            return Command.Call(it.groupValues[1].replace(article, "").trim())
        }
        navigateRule.find(core)?.let {
            return Command.Navigate(it.groupValues[1].replace(article, "").trim())
        }
        return Command.Echo(raw.trim())
    }
}