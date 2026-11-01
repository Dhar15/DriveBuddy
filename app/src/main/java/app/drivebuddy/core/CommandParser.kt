package app.drivebuddy.core

enum class MediaAction { PLAY, PAUSE, STOP, NEXT, PREVIOUS }

/** UP/DOWN move by [Command.Volume.percent] of the full range; SET jumps to that percent. */
enum class VolumeOp { UP, DOWN, SET, MUTE, UNMUTE }

/** When a reminder should fire: a delay from now, or a clock time (today or tomorrow). */
sealed class RemindWhen {
    data class After(val seconds: Int) : RemindWhen()
    /** [pm] is null when no am/pm was said. */
    data class At(val hour: Int, val minute: Int, val pm: Boolean?, val tomorrow: Boolean) : RemindWhen()
}

/** What the router decided the user wants. */
sealed class Command {
    object Empty : Command()
    object Stop : Command()
    object Weather : Command()
    data class Call(val name: String) : Command()
    data class Navigate(val destination: String) : Command()
    data class Media(val action: MediaAction) : Command()
    data class Play(val query: String, val target: String?) : Command()
    data class Volume(val op: VolumeOp, val percent: Int = 0) : Command()
    data class Echo(val text: String) : Command()
    data class Flashlight(val on: Boolean) : Command()
    data class Dnd(val on: Boolean) : Command()
    data class OpenApp(val name: String) : Command()
    data class Timer(val seconds: Int) : Command()

    /** [pm] is null when the driver didn't say am/pm; [AlarmTime.resolve] picks the next sensible one. */
    data class Alarm(val hour: Int, val minute: Int, val pm: Boolean?) : Command()

    /** [whenAt] is null when the driver gave the task but no time. */
    data class Remind(val text: String, val whenAt: RemindWhen?) : Command()
    data class Note(val text: String) : Command()
    /** [body] is null when the driver named the person but not the message. */
    data class Sms(val name: String, val body: String?) : Command()
    data class AddTodo(val item: String, val list: String) : Command()
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

    private val weatherRule = Regex(
        "^(?:(?:what|how)(?: is|'s|s)? (?:the )?(?:weather|temperature)(?: like)?|(?:the )?weather|" +
                "(?:is it|will it|is it going to|will it be) (?:going to )?(?:rain|raining|rainy)|" +
                "how (?:hot|cold|warm) is it|do i need (?:an |a )?umbrella|" +
                "should i (?:take|carry|bring) (?:an |a )?umbrella)" +
                "(?: (?:today|now|outside|right now|here|currently|at the moment))?$"
    )
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
        if (weatherRule.matches(core)) return Command.Weather
        parseSms(core)?.let { return it }
        parseMemory(core)?.let { return it }
        parseSwitch(core)?.let { return it }
        parseClock(core)?.let { return it }
        openAppRule.find(core)?.let {
            val name = it.groupValues[1].replace(article, "").removeSuffix(" app").trim()
            if (name.isNotEmpty()) return Command.OpenApp(name)
        }
        parseVolume(core)?.let { return it }
        return Command.Echo(raw.trim())
    }

    // --- Flashlight, Do Not Disturb, open app ----------------------------------------------
    private val flashlightWord = Regex("\\b(?:flashlight|torch)\\b")
    private val dndWord = Regex("\\b(?:do not disturb|dont disturb|don't disturb|dnd)\\b")
    private val offWord = Regex("\\b(?:off|disable|disabled|deactivate)\\b")
    private val onWord = Regex("\\b(?:on|enable|enabled|activate)\\b")
    private val openAppRule = Regex("^(?:open|launch|start) (.+)$")

    /** "turn off the torch", "dnd on" ... null when it names neither switch or doesn't say on/off. */
    private fun parseSwitch(core: String): Command? {
        val isFlash = flashlightWord.containsMatchIn(core)
        val isDnd = dndWord.containsMatchIn(core)
        if (isFlash == isDnd) return null // neither, or both (ambiguous)
        val on = when {
            offWord.containsMatchIn(core) -> false
            onWord.containsMatchIn(core) -> true
            else -> return null
        }
        return if (isFlash) Command.Flashlight(on) else Command.Dnd(on)
    }

    // --- Timers and alarms ------------------------------------------------------------------
    private const val NUMBERS = "\\d+|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty five|forty|sixty"
    private val numberWords = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20,
        "thirty" to 30, "forty" to 40, "forty five" to 45, "sixty" to 60,
    )
    private val hourAndHalf = Regex("\\b($NUMBERS) hours? and a half\\b")
    private val halfHour = Regex("\\bhalf (?:an )?hour\\b")
    private val durationUnit = Regex("\\b($NUMBERS) (hour|hr|minute|min|second|sec)s?\\b")
    private val alarmTime = Regex("(?:\\bat|\\bfor|\\balarm) (\\d{1,2})(?: (\\d{2}))?(?: (a m|p m|am|pm))?\\b")

    private fun numberOf(token: String): Int = token.toIntOrNull() ?: numberWords[token] ?: 1

    private fun parseSeconds(text: String): Int {
        var rest = text
        var total = 0
        hourAndHalf.findAll(rest).forEach { total += numberOf(it.groupValues[1]) * 3600 + 1800 }
        rest = rest.replace(hourAndHalf, " ")
        if (halfHour.containsMatchIn(rest)) total += 1800
        rest = rest.replace(halfHour, " ")
        durationUnit.findAll(rest).forEach {
            val n = numberOf(it.groupValues[1])
            total += n * when (it.groupValues[2]) {
                "hour", "hr" -> 3600
                "minute", "min" -> 60
                else -> 1
            }
        }
        return total
    }

    /** "set a timer for 10 minutes", "wake me up at 6 30 am", "alarm for 7"; null when it isn't a timer or alarm. */
    private fun parseClock(core: String): Command? {
        val isTimer = Regex("\\btimer\\b").containsMatchIn(core)
        val isAlarm = Regex("\\balarm\\b|\\bwake me\\b").containsMatchIn(core)
        if (!isTimer && !isAlarm) return null
        val seconds = parseSeconds(core)
        val time = alarmTime.find(core)
        // "set an alarm for 20 minutes" is a countdown whichever word was used.
        if (seconds > 0 && (isTimer || time == null || time.groupValues[3].isEmpty() && durationUnit.containsMatchIn(core))) {
            return Command.Timer(seconds.coerceAtMost(24 * 3600))
        }
        if (!isAlarm || time == null) return null
        val hour = time.groupValues[1].toInt()
        val minute = time.groupValues[2].ifEmpty { "0" }.toInt()
        if (hour > 23 || minute > 59) return null
        val pm = when (time.groupValues[3].replace(" ", "")) { "am" -> false; "pm" -> true; else -> null }
        return Command.Alarm(hour, minute, pm)
    }

    // --- Text messages ----------------------------------------------------------------------
    private val smsRule = Regex("^(?:send (?:a |an )?(?:text message|text|sms|message)(?: to)?|text|message|sms) (.+)$")
    private val smsSplit = Regex("^(.+?) (?:saying that|saying|that says|and say|and tell (?:him|her|them)|tells? (?:him|her|them)|say|that) (.+)$")

    private fun parseSms(core: String): Command? {
        val rest = smsRule.find(core)?.groupValues?.get(1) ?: return null
        val split = smsSplit.find(rest)
        val name = (split?.groupValues?.get(1) ?: rest).replace(article, "").trim()
        val body = split?.groupValues?.get(2)?.trim()?.ifEmpty { null }
        return if (name.isEmpty()) null else Command.Sms(name, body)
    }

    // --- Reminders, to-dos and notes --------------------------------------------------------
    private const val LIST_NAMES = "shopping|grocery|groceries|to do|todo|task|tasks|packing|reading|work|home"
    private val remindRule = Regex("^(?:remind me|set (?:a )?reminder(?: for me)?)(?: to| about| that)? (.+)$")
    private val relativeTime = Regex(
        "\\b(?:in|after) (half an hour|(?:$NUMBERS) (?:hour|hr|minute|min|second|sec)s?(?: and a half)?" +
                "(?: (?:and )?(?:$NUMBERS) (?:minute|min|second|sec)s?)*)\\b"
    )
    private val clockTime = Regex("\\b(?:at|by|for) (\\d{1,2})(?: (\\d{2}))?(?: (a m|p m|am|pm))?\\b")
    private val tomorrowWord = Regex("\\btomorrow\\b")
    private val todayWord = Regex("\\b(?:today|tonight)\\b")
    private val addTodoRule = Regex("^(?:add|put) (.+) (?:to|on|in) (?:my |the |our )?(?:($LIST_NAMES) )?list$")
    private val noteRule = Regex("^(?:make a note|take a note|save a note|add a note|note down|write down|remember that|remember)(?: that| to self| to myself| of)? (.+)$")

    private fun listName(raw: String): String = when (raw) {
        "", "to do", "todo", "task", "tasks" -> "to-do"
        "grocery", "groceries", "shopping" -> "shopping"
        else -> raw
    }

    private fun tidy(text: String) = text.replace(spaces, " ").trim().removePrefix("to ").removePrefix("that ").trim()

    private fun parseMemory(core: String): Command? {
        addTodoRule.find(core)?.let {
            val item = tidy(it.groupValues[1])
            if (item.isNotEmpty()) return Command.AddTodo(item, listName(it.groupValues[2]))
        }
        noteRule.find(core)?.let {
            val text = tidy(it.groupValues[1])
            if (text.isNotEmpty()) return Command.Note(text)
        }
        remindRule.find(core)?.let { return parseReminder(it.groupValues[1]) }
        return null
    }

    /** [body] is everything after "remind me (to)": the task plus one time phrase, in either order. */
    private fun parseReminder(body: String): Command {
        var rest = body
        var whenAt: RemindWhen? = null
        val relative = relativeTime.find(rest)
        val clock = clockTime.find(rest)
        val tomorrow = tomorrowWord.containsMatchIn(rest)
        if (relative != null) {
            val seconds = if (relative.groupValues[1] == "half an hour") 1800 else parseSeconds(relative.value)
            if (seconds > 0) {
                whenAt = RemindWhen.After(seconds.coerceAtMost(7 * 24 * 3600))
                rest = rest.replace(relative.value, " ")
            }
        } else if (clock != null) {
            val hour = clock.groupValues[1].toInt()
            val minute = clock.groupValues[2].ifEmpty { "0" }.toInt()
            if (hour <= 23 && minute <= 59) {
                val pm = when (clock.groupValues[3].replace(" ", "")) { "am" -> false; "pm" -> true; else -> null }
                whenAt = RemindWhen.At(hour, minute, pm, tomorrow)
                rest = rest.replace(clock.value, " ")
            }
        }
        rest = rest.replace(tomorrowWord, " ").replace(todayWord, " ")
        val task = tidy(rest)
        return if (task.isEmpty()) Command.Empty else Command.Remind(task, whenAt)
    }

    // --- Volume ---------------------------------------------------------------------------
    // Checked last, after play/call/navigate, so a song called "Turn Down for What" still plays.
    private val bigAmount = Regex("\\b(?:a lot|much|way)\\b")
    private val smallAmount = Regex("\\b(?:a little bit|a little|a bit|slightly|little|bit)\\b")
    private val volumeFiller = setOf("the", "my", "music", "sound", "song", "it", "a", "please", "to", "at", "set", "percent", "level")
    private val volumeNumber = Regex("^(?:volume (\\d{1,3})|(\\d{1,3}) volume)$")
    private val volumeUp = setOf(
        "volume up", "up volume", "turn up", "turn volume up", "turn up volume", "louder", "make louder",
        "increase volume", "raise volume", "crank up", "bump up", "volume higher", "higher volume", "more volume",
    )
    private val volumeDown = setOf(
        "volume down", "down volume", "turn down", "turn volume down", "turn down volume", "quieter", "softer",
        "make quieter", "make softer", "decrease volume", "lower volume", "reduce volume", "volume lower", "less volume",
    )
    private val volumeMax = setOf("max volume", "maximum volume", "full volume", "volume max", "volume maximum", "volume full")
    private val volumeHalf = setOf("half volume", "volume half")

    private fun parseVolume(core: String): Command.Volume? {
        val step = when {
            bigAmount.containsMatchIn(core) -> 25
            smallAmount.containsMatchIn(core) -> 5
            else -> 10
        }
        // Drop the amount words and small filler words, so "turn the music down a bit" becomes "turn down".
        val rest = core.replace(bigAmount, " ").replace(smallAmount, " ")
            .split(" ").filter { it.isNotEmpty() && it !in volumeFiller }.joinToString(" ")
        return when {
            rest == "mute" -> Command.Volume(VolumeOp.MUTE)
            rest == "unmute" -> Command.Volume(VolumeOp.UNMUTE)
            rest in volumeUp -> Command.Volume(VolumeOp.UP, step)
            rest in volumeDown -> Command.Volume(VolumeOp.DOWN, step)
            rest in volumeMax -> Command.Volume(VolumeOp.SET, 100)
            rest in volumeHalf -> Command.Volume(VolumeOp.SET, 50)
            else -> volumeNumber.find(rest)?.let { m ->
                val n = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt()
                Command.Volume(VolumeOp.SET, n.coerceIn(0, 100))
            }
        }
    }
}