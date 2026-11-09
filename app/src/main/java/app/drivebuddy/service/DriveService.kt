package app.drivebuddy.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import app.drivebuddy.R
import app.drivebuddy.audio.AckVoice
import app.drivebuddy.audio.Cue
import app.drivebuddy.audio.Speaker
import app.drivebuddy.audio.SpeechCapture
import app.drivebuddy.audio.WakeWord
import app.drivebuddy.command.Command
import app.drivebuddy.command.CommandParser
import app.drivebuddy.command.MediaAction
import app.drivebuddy.command.YesNo
import app.drivebuddy.common.LatencyLog
import app.drivebuddy.common.LatencyTracker
import app.drivebuddy.common.LocationHelper
import app.drivebuddy.common.LogText
import app.drivebuddy.common.Mark
import app.drivebuddy.features.chat.BuddyChat
import app.drivebuddy.features.chat.ChatQuota
import app.drivebuddy.features.chat.LlmClient
import app.drivebuddy.features.clock.AlarmHandler
import app.drivebuddy.features.clock.TimeText
import app.drivebuddy.features.communication.CommunicationHandler
import app.drivebuddy.features.music.MusicHandler
import app.drivebuddy.features.music.spotify.SpotifyApi
import app.drivebuddy.features.music.spotify.SpotifyAuth
import app.drivebuddy.features.navigation.Journeys
import app.drivebuddy.features.navigation.SavedPlaces
import app.drivebuddy.features.nearby.NearbyHandler
import app.drivebuddy.features.news.NewsHandler
import app.drivebuddy.features.phone.PhoneActions
import app.drivebuddy.features.phone.PhoneControls
import app.drivebuddy.features.weather.WeatherHandler
import app.drivebuddy.ui.ChoiceCard
import app.drivebuddy.ui.DriveBus
import app.drivebuddy.ui.DriveState
import app.drivebuddy.ui.MainActivity
import app.drivebuddy.ui.ReadAlong
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Milestone 1 service: keeps the microphone foreground service alive for a drive and runs one
 * interaction per "Talk" tap: cue -> listen -> ack cue -> transcript -> rule router -> spoken reply -> log.
 * A wake word ("Hi Friend") will later call [beginInteraction] the same way the Talk button does.
 */
class DriveService : Service() {

    companion object {
        const val ACTION_START = "app.drivebuddy.START"
        const val ACTION_TALK = "app.drivebuddy.TALK"
        const val ACTION_STOP = "app.drivebuddy.STOP"
        const val ACTION_COMMAND = "app.drivebuddy.COMMAND"
        const val EXTRA_TEXT = "text"
        private const val CHANNEL_ID = "drivebuddy_drive"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "DriveBuddy"
        private const val INTERACTION_TIMEOUT_MS = 15_000L

        /** Set by the activity so the screen can show status. Always invoked on the main thread. */
        @Volatile
        var statusListener: ((String) -> Unit)? = null

        @Volatile
        var lastStatus: String = "Stopped"
    }

    private val main = Handler(Looper.getMainLooper())
    private val tracker = LatencyTracker()
    private lateinit var log: LatencyLog
    private lateinit var cue: Cue
    private lateinit var ackVoice: AckVoice
    private lateinit var speaker: Speaker
    private lateinit var capture: SpeechCapture
    private lateinit var audio: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var busy = false
    private var foregroundStarted = false
    private var speaking = false
    private var interrupted = false
    private lateinit var actions: PhoneActions
    private lateinit var anchor: OverlayAnchor
    private lateinit var spotifyAuth: SpotifyAuth
    private lateinit var spotify: SpotifyApi
    private lateinit var wake: WakeWord
    @Volatile private var destroyed = false
    private var pausedForListening = false
    private var keepMusicPaused = false
    private class Ask(val prompt: String, val quiet: Boolean, val onAnswer: (String) -> Unit)
    private var pendingAsk: Ask? = null                 // a question we are about to ask or have just asked
    private var quietAnswer = false // the answer we are listening for must not be logged
    private var answerHandler: ((String) -> Unit)? = null // set while we are listening for the answer

    private val buddy = BuddyChat(maxExchanges = 8)
    private var inChat = false // true from the first buddy reply until the conversation ends
    private lateinit var llm: LlmClient
    private lateinit var quota: ChatQuota

    // What feature handlers may ask of this service. Calls are qualified because the names match ours.
    private val conversation = object : Conversation {
        override val context: Context get() = this@DriveService
        override val destroyed: Boolean get() = this@DriveService.destroyed
        override fun reply(text: String, quiet: Boolean, onProgress: ((Int) -> Unit)?, afterSpeaking: (() -> Unit)?) =
            this@DriveService.reply(text, quiet, onProgress, afterSpeaking)
        override fun ask(prompt: String, onProgress: ((Int) -> Unit)?, quiet: Boolean, onAnswer: (String) -> Unit) =
            this@DriveService.ask(prompt, onProgress, quiet, onAnswer)
        override fun publish(status: String, state: DriveState) = this@DriveService.publish(status, state)
        override fun post(block: () -> Unit) { main.post { block() } }
        override fun pauseWatchdog() = main.removeCallbacks(watchdog)
        override fun finish() = finishInteraction()
        override fun showCards(card: ChoiceCard) {
            DriveBus.showChoices(card)
            DriveBus.choiceTap = { token -> onChoiceTapped(token) }
            bringScreenToFront()
        }
        override fun slideAlong(text: String, markers: List<String>) = this@DriveService.slideAlong(text, markers)
        override fun locationReady(feature: String) = this@DriveService.locationReady(feature)
        override fun startNavigation(name: String, query: String) = this@DriveService.startNearbyNavigation(name, query)
        override fun askYesNo(prompt: String, quiet: Boolean, onYes: () -> Unit) =
            this@DriveService.askYesNo(prompt, onYes = onYes, quiet = quiet)
        override fun speakLate(text: String) = this@DriveService.speakLate(text)
    }
    private val news by lazy { NewsHandler(conversation) }
    private val nearby by lazy { NearbyHandler(conversation) }
    private val weather by lazy { WeatherHandler(conversation) }
    private val alarms by lazy { AlarmHandler(conversation) }
    private val comms by lazy { CommunicationHandler(conversation, actions) }
    private val music by lazy { MusicHandler(conversation, actions, spotifyAuth, spotify) }

    // If the recogniser never calls back, give up instead of staying busy forever.
    private val watchdog = Runnable {
        Log.w(TAG, "interaction timed out after $INTERACTION_TIMEOUT_MS ms")
        answerHandler = null
        capture.destroy()
        tracker.sttEngine = capture.engineName
        if (inChat) {
            Log.i(TAG, "chat: the driver went quiet, ending the conversation")
            tracker.command = "chat: ended on silence"
            finishInteraction()
            return@Runnable
        }
        tracker.command = "error: timeout"
        reply("Sorry, I didn't catch that.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        log = LatencyLog(this)
        actions = PhoneActions(this)
        anchor = OverlayAnchor(this)
        spotifyAuth = SpotifyAuth(this)
        spotify = SpotifyApi(spotifyAuth)
        llm = LlmClient()
        quota = ChatQuota(this)
        cue = Cue()
        ackVoice = AckVoice(this)
        speaker = Speaker(this)
        capture = SpeechCapture(this)
        wake = WakeWord(this, onProblem = { why ->
            Log.w(TAG, "wake word problem: $why")
            publish("Wake word problem: $why", DriveState.ERROR)
        }) {
            // Called from the detector's thread: hop to the main thread, then act like a Talk tap.
            main.post {
                if (!busy && !destroyed) {
                    Log.i(TAG, "triggered by wake word")
                    beginInteraction()
                }
            }
        }
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            publish("Microphone permission missing", DriveState.ERROR)
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TALK -> {
                ensureForeground()
                beginInteraction()
            }
            ACTION_COMMAND -> {
                ensureForeground()
                runTyped(intent.getStringExtra(EXTRA_TEXT).orEmpty())
            }
            else -> {
                ensureForeground()
                wake.start()
                speaker.speak("Ready.", onStart = {}, onDone = {})
                publish("Ready. Say \"Hi Friend\" or tap Talk.")
            }
        }
        // Not sticky: Android 14+ will not let a killed microphone service restart itself from the background.
        return START_NOT_STICKY
    }

    private fun ensureForeground() {
        if (foregroundStarted) return
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        foregroundStarted = true
    }

    fun beginInteraction() {
        if (busy) {
            if (speaking) {
                Log.i(TAG, "Talk pressed while speaking: interrupting")
                interrupted = true // cancels any action waiting for the speech to finish
                pendingAsk = null
                answerHandler = null
                speaker.stop()     // finishes the old interaction, which frees us to start a new one
            } else {
                Log.i(TAG, "Talk ignored: previous interaction still running")
                publish("Still busy with the last request...", DriveBus.current.state)
                return
            }
        }
        busy = true
        wake.stop() // free the microphone for the speech recogniser
        pausedForListening = actions.pauseIfPlaying()
        keepMusicPaused = false
        anchor.show() // keeps a visible window so Maps/Spotify may be opened from the background
        tracker.reset()
        tracker.mark(Mark.TRIGGER)
        requestFocus()
        cue.listening()
        publish("Listening...", DriveState.LISTENING)
        main.postDelayed(watchdog, INTERACTION_TIMEOUT_MS)
        // Short delay so our own beep is not picked up as speech.
        // The beep, then the spoken "mhm?"; listening starts only after our own voice has finished,
        // plus a short gap so its tail isn't picked up as speech.
        main.postDelayed({
            ackVoice.play { main.postDelayed({ if (!destroyed) startCapture() }, 120) }
        }, 150)
    }

    /** Runs a command that was tapped, not spoken. Same router, same spoken reply, no listening step. */
    private fun runTyped(text: String) {
        if (busy) {
            Log.i(TAG, "typed command ignored, still busy: \"$text\"")
            return
        }
        Log.i(TAG, "typed command: \"$text\"")
        busy = true
        wake.stop() // same as a spoken request: free the microphone while we respond
        pausedForListening = actions.pauseIfPlaying()
        keepMusicPaused = false
        anchor.show()
        tracker.reset()
        // There was no listening, so these marks land together. The engine name lets you filter these runs out of latency summaries.
        tracker.mark(Mark.TRIGGER)
        tracker.mark(Mark.LISTENING)
        tracker.mark(Mark.SPEECH_END)
        tracker.mark(Mark.ACK_CUE)
        tracker.mark(Mark.STT_FINAL)
        tracker.sttEngine = "typed"
        tracker.transcript = text
        requestFocus()
        publish("Thinking...", DriveState.THINKING)
        respond(CommandParser.parse(text))
    }

    private fun startCapture() {
        capture.start(object : SpeechCapture.Callback {
            override fun onListening() {
                tracker.mark(Mark.LISTENING)
            }

            override fun onSpeechEnd() {
                tracker.mark(Mark.SPEECH_END)
                cue.ack()
                tracker.mark(Mark.ACK_CUE)
                publish("Thinking...", DriveState.THINKING)
            }

            override fun onResult(text: String) {
                main.removeCallbacks(watchdog) // we have an answer; no stale timer may fire while we reply
                val handler = answerHandler
                val quiet = handler != null && quietAnswer
                quietAnswer = false
                tracker.mark(Mark.SPEECH_END)
                tracker.mark(Mark.STT_FINAL)
                tracker.sttEngine = capture.engineName
                tracker.transcript = if (quiet) "(not logged)" else text
                Log.i(TAG, "transcript=${LogText.shown(text, quiet)} engine=${capture.engineName}")
                if (handler != null) {
                    answerHandler = null
                    Log.i(TAG, "follow-up answer: ${LogText.shown(text, quiet)}")
                    handler(text)
                } else {
                    respond(CommandParser.parse(text))
                }
            }

            override fun onError(message: String) {
                answerHandler = null
                tracker.sttEngine = capture.engineName
                tracker.command = "error: $message"
                Log.w(TAG, "recognition failed: $message (engine=${capture.engineName})")
                val noInternet = message.contains("network") || message.contains("server")
                if (inChat && !noInternet) {
                    Log.i(TAG, "chat: the driver went quiet, ending the conversation")
                    tracker.command = "chat: ended on silence"
                    finishInteraction()
                    return
                }
                reply(
                    if (noInternet) "I can't reach the speech service. Please check your internet connection."
                    else "Sorry, I didn't catch that."
                )
            }
        })
    }

    private fun respond(command: Command) {
        Log.i(TAG, "routed: ${command.javaClass.simpleName}")
        tracker.mark(Mark.ROUTED)
        tracker.command = command.javaClass.simpleName
        keepMusicPaused = command is Command.Play ||
                (command is Command.Media && (command.action == MediaAction.PAUSE || command.action == MediaAction.STOP))
        when (command) {
            is Command.Empty -> reply("I didn't catch that.")
            is Command.Stop -> reply("Okay.")
            is Command.Echo -> handleChat(command.text)
            is Command.Media -> when {
                actions.mediaControl(command.action) -> reply("Okay.")
                !actions.hasNotificationAccess() -> reply("I need notification access to control music.")
                command.action == MediaAction.STOP -> reply("Okay.")
                else -> reply("I don't see a music app to control.")
            }
            is Command.Play -> music.play(command)
            is Command.Volume -> music.volume(command)
            is Command.Call -> comms.call(command.name)
            is Command.Sms -> comms.sms(command.name, command.body)
            is Command.AlarmInfo -> alarms.info(command.showList)
            is Command.CancelAlarm -> alarms.cancel()
            is Command.Navigate -> handleNavigate(command.destination)
            is Command.Flashlight -> reply(PhoneControls.flashlight(this, command.on))
            is Command.Dnd -> reply(PhoneControls.dnd(this, command.on))
            is Command.OpenApp -> reply(PhoneControls.openApp(this, command.name))
            is Command.Timer -> reply(PhoneControls.timer(this, command.seconds))
            is Command.Alarm -> reply(PhoneControls.alarm(this, command.hour, command.minute, command.pm))
            is Command.Remind -> reply(PhoneControls.reminder(this, command.text, command.whenAt))
            is Command.AddTodo -> reply(PhoneControls.listItem(this, command.item, command.list))
            is Command.Note -> reply(PhoneControls.note(this, command.text))
            is Command.Nearby -> nearby.handle(command.what)
            is Command.News -> news.handle(command.world)
            Command.Weather -> weather.handle()
            Command.Time -> { val now = LocalTime.now(); reply(TimeText.time(now.hour, now.minute)) }
            Command.Date -> {
                val today = LocalDate.now()
                reply(
                    TimeText.date(
                        today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                        today.dayOfMonth,
                        today.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                    )
                )
            }
        }
    }

    /** A message that arrives after the interaction already finished (a failure we only learned about later). */
    private fun speakLate(text: String) {
        if (busy || destroyed) {
            Log.i(TAG, "late message dropped, assistant is busy: \"$text\"")
            return
        }
        Log.i(TAG, "late message: \"$text\"")
        busy = true
        wake.stop()
        tracker.reset()
        tracker.command = "late: spotify"
        requestFocus()
        reply(text)
    }

    /** A button on the pick-one card was tapped: cut off the read-out or the listening and deliver it as the answer. */
    private fun onChoiceTapped(token: String) {
        val handler = pendingAsk?.onAnswer ?: answerHandler
        if (handler == null) {
            Log.i(TAG, "choice tapped but nothing is waiting for it: $token")
            return
        }
        Log.i(TAG, "choice tapped: $token (speaking=$speaking)")
        main.removeCallbacks(watchdog)
        pendingAsk = null
        answerHandler = null
        capture.destroy() // stop listening, if we were
        if (speaking) {
            interrupted = true // cancels the follow-up that was due after the read-out
            speaker.stop()     // ends the read-out
        }
        main.postDelayed({
            if (destroyed) return@postDelayed
            busy = true // the old interaction has just finished; this tap starts a new one
            wake.stop()
            requestFocus()
            handler("tap:$token")
        }, 150)
    }

    /** If the home screen isn't showing (Maps is open, say), bring it forward so the list is visible. */
    private fun bringScreenToFront() {
        if (DriveBus.listener != null) return // already on screen
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            Log.i(TAG, "choices: brought the screen to the front")
        } catch (e: Exception) {
            Log.w(TAG, "choices: could not show the screen: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Anything the parser doesn't recognise becomes a chat with the buddy. */
    private fun handleChat(text: String) {
        if (!quota.canUse()) {
            Log.i(TAG, "chat: daily allowance used up")
            reply("I've used up my chatting for today. Try again tomorrow.")
            return
        }
        inChat = true
        buddy.reset() // a new wake-up starts a new conversation
        askBuddy(text)
    }

    private fun askBuddy(text: String) {
        Log.i(TAG, "chat: sending to the buddy (${buddy.exchanges} earlier exchanges)")
        buddy.addUser(text)
        publish("Thinking...", DriveState.THINKING)
        main.removeCallbacks(watchdog) // the network call has its own timeouts
        quota.record()
        Thread {
            val result = llm.chat(buddy.messages())
            main.post {
                if (destroyed) return@post
                when (result) {
                    is LlmClient.Result.Reply -> {
                        val spoken = BuddyChat.Companion.tidy(result.text)
                        if (spoken.isEmpty()) {
                            reply("Hmm, I'm lost for words.")
                        } else {
                            buddy.addAssistant(spoken)
                            if (buddy.canContinue) {
                                ask(spoken, quiet = true) { heard -> onChatAnswer(heard) } // speaks it, then listens for the driver's answer
                            } else {
                                Log.i(TAG, "chat: reached the end of this conversation")
                                reply(spoken, quiet = true)
                            }
                        }
                    }
                    LlmClient.Result.NotConfigured -> reply("My chat brain isn't set up yet.")
                    LlmClient.Result.RateLimited -> reply("I'm out of chat for the moment. Try again in a minute.")
                    LlmClient.Result.Offline -> reply("I can't reach my chat brain. Please check your internet connection.")
                    is LlmClient.Result.Failed -> {
                        Log.w(TAG, "chat: failed: ${result.why}")
                        reply("Sorry, I couldn't think of anything.")
                    }
                }
            }
        }.start()
    }

    /** The driver spoke again during a chat: goodbye, a real command, or more conversation. */
    private fun onChatAnswer(heard: String) {
        when {
            heard.isBlank() -> {
                Log.i(TAG, "chat: nothing heard, ending the conversation")
                finishInteraction()
            }
            BuddyChat.Companion.isGoodbye(heard) -> {
                Log.i(TAG, "chat: the driver ended the conversation")
                reply("Anytime.")
            }
            else -> {
                val command = CommandParser.parse(heard)
                if (command is Command.Echo) {
                    askBuddy(heard)
                } else {
                    Log.i(TAG, "chat: a real command came up mid-chat, running it")
                    inChat = false // a command's own follow-ups should behave normally
                    buddy.reset()
                    respond(command)
                }
            }
        }
    }

    /** True when location can be read. Otherwise it says (or asks) what to fix and returns false. */
    private fun locationReady(feature: String): Boolean {
        if (!LocationHelper.hasPermission(this)) {
            Log.w(TAG, "$feature: no location permission")
            reply("I need location permission for that. Please allow it in DriveBuddy's permissions.")
            return false
        }
        if (!LocationHelper.isLocationOn(this)) {
            Log.w(TAG, "$feature: location services are off")
            askYesNo(
                "Your phone's location is turned off. Should I open the location settings?",
                onYes = {
                    reply("Opening location settings. Turn location on, then ask me again.") {
                        LocationHelper.openSettings(this)
                    }
                },
            )
            return false
        }
        return true
    }

    /** Returns a callback for the voice's position: moves the card to each item the moment its first word is spoken. */
    private fun slideAlong(text: String, markers: List<String>): (Int) -> Unit {
        val offsets = ReadAlong.offsets(text, markers)
        Log.d(TAG, "choices: slide markers found ${offsets.count { it >= 0 }} of ${markers.size}")
        var shown = 0
        return { spoken ->
            val target = offsets.indexOfLast { it in 0..spoken }
            if (target > shown) {
                shown = target
                DriveBus.slideTo(target)
            }
        }
    }

    private fun handleNavigate(spoken: String) {
        if (!actions.canOpenOtherApps()) {
            reply("I need permission to display over other apps before I can open maps.")
            return
        }
        val query = SavedPlaces.resolve(this, spoken)
        reply("Navigating to $spoken. Tap Talk to cancel.") {
            if (actions.navigate(query)) {
                Log.i(TAG, "navigation started: \"$spoken\"")
                Journeys.record(this, spoken, query)
            } else {
                Log.w(TAG, "navigation to \"$spoken\" could not be started")
            }
        }
    }

    /** Opens Maps for a picked nearby place and records it as the last trip. */
    private fun startNearbyNavigation(name: String, query: String): Boolean {
        val started = actions.navigate(query)
        if (started) {
            Log.i(TAG, "navigation started: nearby pick")
            Journeys.record(this, name, query)
        } else {
            Log.w(TAG, "nearby navigation could not be started")
        }
        return started
    }

    private fun reply(
        text: String,
        quiet: Boolean = false,
        onProgress: ((Int) -> Unit)? = null,
        afterSpeaking: (() -> Unit)? = null,
    ) {
        speaking = true
        interrupted = false
        publish("Speaking...", DriveState.SPEAKING)
        speaker.speak(
            text,
            onStart = { tracker.mark(Mark.TTS_START) },
            onDone = {
                tracker.mark(Mark.TTS_DONE)
                if (afterSpeaking != null && !interrupted) afterSpeaking()
                if (pendingAsk != null && !interrupted) startFollowUp() else finishInteraction()
            },
            onProgress = onProgress,
            quiet = quiet,
        )
    }

    /** Says [prompt], then listens for the answer and hands the text to [onAnswer]. */
    private fun ask(prompt: String, onProgress: ((Int) -> Unit)? = null, quiet: Boolean = false, onAnswer: (String) -> Unit) {
        Log.i(TAG, "follow-up: asking ${LogText.shown(prompt, quiet)}")
        pendingAsk = Ask(prompt, quiet, onAnswer)
        reply(prompt, onProgress = onProgress, quiet = quiet)
    }

    /** A yes/no question. A mumbled answer is asked once more, then treated as no. */
    private fun askYesNo(
        prompt: String,
        onYes: () -> Unit,
        onNo: () -> Unit = { reply("Okay, cancelled.") },
        retried: Boolean = false,
        quiet: Boolean = false,
    ) {
        ask(prompt, quiet = quiet) { text ->
            when (YesNo.parse(text)) {
                true -> onYes()
                false -> onNo()
                null -> if (!retried) askYesNo("Sorry, was that a yes or a no?", onYes, onNo, true)
                else reply("Okay, cancelled.")
            }
        }
    }

    /** Called when the question has been spoken: listen again straight away, no wake word needed. */
    private fun startFollowUp() {
        val asked = pendingAsk ?: return finishInteraction()
        pendingAsk = null
        answerHandler = asked.onAnswer
        speaking = false
        quietAnswer = asked.quiet
        Log.i(TAG, "follow-up: listening for an answer to ${LogText.shown(asked.prompt, asked.quiet)}")
        cue.listening()
        publish("Listening...", DriveState.LISTENING)
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, INTERACTION_TIMEOUT_MS)
        main.postDelayed({ if (!destroyed) startCapture() }, 150)
    }

    private fun finishInteraction() {
        main.removeCallbacks(watchdog)
        speaking = false
        inChat = false
        pendingAsk = null
        quietAnswer = false
        DriveBus.showChoices(null)
        DriveBus.choiceTap = null
        answerHandler = null
        log.append(tracker.toJson())
        abandonFocus()
        if (pausedForListening && !keepMusicPaused) actions.mediaControl(MediaAction.PLAY)
        pausedForListening = false
        busy = false
        val summary = if (tracker.command.startsWith("error")) tracker.command else "heard \"${tracker.transcript}\""
        publish("Ready. Last: $summary")
        // Resume listening for "Hi Friend" shortly after, unless a new interaction has already begun.
        main.postDelayed({ if (!busy && !destroyed) wake.start() }, 300)
    }

    private fun requestFocus() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        focusRequest = request
        audio.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun publish(status: String, state: DriveState = DriveState.IDLE) {
        lastStatus = status
        DriveBus.post(state, status)
        main.post { statusListener?.invoke(status) }
    }

    private fun createChannel() {
        val channel =
            NotificationChannel(CHANNEL_ID, "Drive session", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val talk = PendingIntent.getService(
            this, 1, Intent(this, DriveService::class.java).setAction(ACTION_TALK), flags,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, DriveService::class.java).setAction(ACTION_STOP), flags,
        )
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), flags)
        val icon = R.drawable.ic_stat_drivebuddy
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle("DriveBuddy")
            .setContentText("Tap Talk to speak")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, icon), "Talk", talk).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, icon), "Stop", stop).build())
            .build()
    }

    override fun onDestroy() {
        destroyed = true
        wake.release()
        anchor.hide()
        abandonFocus()
        capture.destroy()
        speaker.shutdown()
        cue.release()
        ackVoice.release()
        main.removeCallbacksAndMessages(null)
        publish("Stopped", DriveState.OFF)
        super.onDestroy()
    }
}