package app.drivebuddy

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
import app.drivebuddy.audio.Cue
import app.drivebuddy.audio.Speaker
import app.drivebuddy.audio.SpeechCapture
import app.drivebuddy.core.Command
import app.drivebuddy.core.CommandParser
import app.drivebuddy.core.LatencyTracker
import app.drivebuddy.core.Mark
import app.drivebuddy.core.MatchResult
import app.drivebuddy.core.NameMatcher
import app.drivebuddy.spotify.SpotifyApi
import app.drivebuddy.spotify.SpotifyAuth
import app.drivebuddy.audio.WakeWord
import app.drivebuddy.core.MediaAction
import app.drivebuddy.core.VolumeOp
import app.drivebuddy.audio.AckVoice
import app.drivebuddy.core.Candidate
import app.drivebuddy.core.SmsText
import app.drivebuddy.core.YesNo
import app.drivebuddy.core.WeatherText
import app.drivebuddy.core.NearbyText
import app.drivebuddy.core.RankedPlace
import app.drivebuddy.core.PlaceChoice
import app.drivebuddy.core.PlayIntent
import app.drivebuddy.core.TrackCandidate
import app.drivebuddy.core.TrackPicker
import kotlin.concurrent.thread

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
        private const val SPOTIFY = "com.spotify.music"
        private const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
        private const val RECONNECT_SPOTIFY = "Spotify isn't connected. Open Permissions and tap Connect Spotify."

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
    private class Ask(val prompt: String, val onAnswer: (String) -> Unit)
    private var pendingAsk: Ask? = null                 // a question we are about to ask or have just asked
    private var answerHandler: ((String) -> Unit)? = null // set while we are listening for the answer

    // If the recogniser never calls back, give up instead of staying busy forever.
    private val watchdog = Runnable {
        Log.w(TAG, "interaction timed out after $INTERACTION_TIMEOUT_MS ms")
        answerHandler = null
        capture.destroy()
        tracker.sttEngine = capture.engineName
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
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
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
                // Some recognisers skip onEndOfSpeech; make sure the mark exists.
                tracker.mark(Mark.SPEECH_END)
                tracker.mark(Mark.STT_FINAL)
                tracker.sttEngine = capture.engineName
                tracker.transcript = text
                Log.i(TAG, "transcript=\"$text\" engine=${capture.engineName}")
                val handler = answerHandler
                if (handler != null) {
                    answerHandler = null
                    Log.i(TAG, "follow-up answer: \"$text\"")
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
                reply(
                    if (noInternet) "I can't reach the speech service. Please check your internet connection."
                    else "Sorry, I didn't catch that."
                )
            }
        })
    }

    private fun respond(command: Command) {
        tracker.mark(Mark.ROUTED)
        tracker.command = command.javaClass.simpleName
        keepMusicPaused = command is Command.Play ||
                (command is Command.Media && (command.action == MediaAction.PAUSE || command.action == MediaAction.STOP))
        when (command) {
            is Command.Empty -> reply("I didn't catch that.")
            is Command.Stop -> reply("Okay.")
            is Command.Echo -> reply("You said: ${command.text}")
            is Command.Media -> when {
                actions.mediaControl(command.action) -> reply("Okay.")
                !actions.hasNotificationAccess() -> reply("I need notification access to control music.")
                command.action == MediaAction.STOP -> reply("Okay.")
                else -> reply("I don't see a music app to control.")
            }
            is Command.Play -> handlePlay(command)
            is Command.Volume -> handleVolume(command)
            is Command.Call -> handleCall(command.name)
            is Command.Navigate -> handleNavigate(command.destination)
            is Command.Flashlight -> reply(PhoneControls.flashlight(this, command.on))
            is Command.Dnd -> reply(PhoneControls.dnd(this, command.on))
            is Command.OpenApp -> reply(PhoneControls.openApp(this, command.name))
            is Command.Timer -> reply(PhoneControls.timer(this, command.seconds))
            is Command.Alarm -> reply(PhoneControls.alarm(this, command.hour, command.minute, command.pm))
            is Command.Remind -> reply(PhoneControls.reminder(this, command.text, command.whenAt))
            is Command.AddTodo -> reply(PhoneControls.listItem(this, command.item, command.list))
            is Command.Note -> reply(PhoneControls.note(this, command.text))
            is Command.Sms -> handleSms(command.name, command.body)
            is Command.Nearby -> handleNearby(command.what)
            Command.Weather -> handleWeather()
        }
    }

    private fun handlePlay(command: Command.Play) {
        if (command.target == "youtube music") {
            if (!actions.isInstalled(YOUTUBE_MUSIC)) {
                reply("I don't see YouTube Music on this phone.")
                return
            }
            reply("Playing ${command.query}.") {
                if (!actions.playFromSearch(command.query, "youtube music")) Log.w(TAG, "could not start playback")
            }
            return
        }
        if (!spotifyAuth.isConnected()) {
            reply(RECONNECT_SPOTIFY)
            return
        }
        when (val intent = PlayIntent.parse(command.query)) {
            is PlayIntent.Artist -> { Log.i(TAG, "play: artist request"); playArtist(intent.name); return }
            PlayIntent.Any -> { Log.i(TAG, "play: any song (liked songs, shuffled)"); playLiked(); return }
            is PlayIntent.Song -> Unit
            is PlayIntent.Playlist -> { Log.i(TAG, "play: my playlist request"); playPlaylist(intent.name); return }
            PlayIntent.AnyPlaylist -> { Log.i(TAG, "play: any of my playlists"); playPlaylist(null); return }
        }
        publish("Asking Spotify...", DriveState.THINKING)
        // Network calls stay off the main thread. Search first, speak, and only then start the music.
        Thread {
            main.removeCallbacks(watchdog) // waking Spotify can take several seconds
            val track = when (val found = spotify.search(command.query)) {
                is SpotifyApi.SearchResult.Found -> found.track
                SpotifyApi.SearchResult.NotFound -> { main.post { reply("I couldn't find ${command.query} on Spotify.") }; return@Thread }
                SpotifyApi.SearchResult.NotConnected -> { main.post { reply(RECONNECT_SPOTIFY) }; return@Thread }
                is SpotifyApi.SearchResult.Failed -> {
                    Log.w(TAG, "spotify search failed: ${found.why}")
                    main.post { reply("I couldn't reach Spotify.") }
                    return@Thread
                }
            }
            main.post { if (!destroyed) announceThenPlay(track) }
        }.start()
    }

    private fun handleVolume(command: Command.Volume) {
        val change = actions.changeVolume(command.op, command.percent)
        if (change == null) {
            Log.w(TAG, "volume ${command.op} failed")
            reply("I couldn't change the volume.")
            return
        }
        Log.i(TAG, "volume ${command.op} ${command.percent}%: ${change.before}% -> ${change.after}%, muted=${change.muted}")
        reply(
            when {
                command.op == VolumeOp.MUTE -> "Muted."
                command.op == VolumeOp.UP && change.after == change.before -> "Volume is already at maximum."
                command.op == VolumeOp.DOWN && change.after == change.before -> "Volume is already at minimum."
                else -> "Volume ${change.after} percent."
            }
        )
    }

    /** Speaks first so the voice is heard at full volume, then starts the music. */
    private fun announceThenPlay(track: SpotifyApi.Track) {
        reply("Playing ${track.name} by ${track.artist}.") {
            Thread {
                val problem = spotifyFailureMessage(track)
                if (problem != null) main.post { speakLate(problem) }
            }.start()
        }
    }

    private fun playArtist(name: String) {
        publish("Asking Spotify...", DriveState.THINKING)
        Thread {
            main.removeCallbacks(watchdog)
            when (val found = spotify.findArtist(name)) {
                is SpotifyApi.ArtistResult.Found ->
                    main.post { if (!destroyed) announceThenPlayContext(found.artist.uri, found.artist.name, "songs by ${found.artist.name}") }
                SpotifyApi.ArtistResult.NotFound -> main.post { reply("I couldn't find an artist called $name on Spotify.") }
                SpotifyApi.ArtistResult.NotConnected -> main.post { reply(RECONNECT_SPOTIFY) }
                is SpotifyApi.ArtistResult.Failed -> {
                    Log.w(TAG, "spotify artist search failed: ${found.why}")
                    main.post { reply("I couldn't reach Spotify.") }
                }
            }
        }.start()
    }

    private fun playLiked() {
        publish("Asking Spotify...", DriveState.THINKING)
        Thread {
            main.removeCallbacks(watchdog)
            when (val liked = spotify.likedSongs()) {
                is SpotifyApi.LikedResult.Ready ->
                    main.post { if (!destroyed) announceThenPlayContext(liked.contextUri, "liked songs", "your liked songs") }
                SpotifyApi.LikedResult.Empty -> main.post { reply("You don't have any liked songs yet.") }
                SpotifyApi.LikedResult.NeedsPermission ->
                    main.post { reply("I need one more Spotify permission. Open Permissions and tap Connect Spotify again.") }
                SpotifyApi.LikedResult.NotConnected -> main.post { reply(RECONNECT_SPOTIFY) }
                is SpotifyApi.LikedResult.Failed -> {
                    Log.w(TAG, "spotify liked songs failed: ${liked.why}")
                    main.post { reply("I couldn't reach Spotify.") }
                }
            }
        }.start()
    }

    /** [name] null means "any of my playlists". */
    private fun playPlaylist(name: String?) {
        publish("Asking Spotify...", DriveState.THINKING)
        Thread {
            main.removeCallbacks(watchdog)
            when (val result = spotify.myPlaylists()) {
                is SpotifyApi.PlaylistsResult.Ready -> {
                    val list = result.playlists
                    val chosen = if (name == null) {
                        list.random()
                    } else {
                        val i = TrackPicker.best(name, list.map { TrackCandidate(it.name, emptyList(), 0) })
                        if (i < 0) null else list[i]
                    }
                    if (chosen == null) {
                        Log.i(TAG, "play: no playlist matched among ${list.size}")
                        main.post { reply("I couldn't find a playlist called $name.") }
                    } else {
                        Log.i(TAG, "play: playlist chosen from ${list.size}")
                        main.post { if (!destroyed) announceThenPlayContext(chosen.uri, chosen.name, "your playlist ${chosen.name}") }
                    }
                }
                SpotifyApi.PlaylistsResult.Empty -> main.post { reply("You don't have any playlists yet.") }
                SpotifyApi.PlaylistsResult.NeedsPermission ->
                    main.post { reply("I need one more Spotify permission. Open Permissions and tap Connect Spotify again.") }
                SpotifyApi.PlaylistsResult.NotConnected -> main.post { reply(RECONNECT_SPOTIFY) }
                is SpotifyApi.PlaylistsResult.Failed -> {
                    Log.w(TAG, "spotify playlists failed: ${result.why}")
                    main.post { reply("I couldn't reach Spotify.") }
                }
            }
        }.start()
    }

    /** Speaks first, then starts the shuffled context, like announceThenPlay does for a single track. */
    private fun announceThenPlayContext(uri: String, label: String, spoken: String) {
        reply("Playing $spoken, shuffled.") {
            Thread {
                val problem = failureText(spotify.playContext(uri, label) { actions.openApp(SPOTIFY) })
                if (problem != null) main.post { speakLate(problem) }
            }.start()
        }
    }

    /** Runs on a background thread. Returns what to say if playback did not start, or null when it did. */
    private fun spotifyFailureMessage(track: SpotifyApi.Track): String? =
        when (val played = spotify.play(track) { actions.openApp(SPOTIFY) }) {
            SpotifyApi.PlayResult.Started -> null
            SpotifyApi.PlayResult.NoDevice -> "Spotify isn't running on this phone. Open Spotify once and try again."
            SpotifyApi.PlayResult.NotConnected -> RECONNECT_SPOTIFY
            SpotifyApi.PlayResult.NotPlaying -> "Spotify accepted it but didn't start playing. Please try again."
            SpotifyApi.PlayResult.Refused -> "Spotify refused to play. Check that Premium is active."
            is SpotifyApi.PlayResult.Failed -> {
                Log.w(TAG, "spotify play failed: ${played.why}")
                "Spotify didn't respond."
            }
        }

    private fun failureText(played: SpotifyApi.PlayResult): String? =
        when (played) {
            SpotifyApi.PlayResult.Started -> null
            SpotifyApi.PlayResult.NoDevice -> "Spotify isn't running on this phone. Open Spotify once and try again."
            SpotifyApi.PlayResult.NotConnected -> RECONNECT_SPOTIFY
            SpotifyApi.PlayResult.NotPlaying -> "Spotify accepted it but didn't start playing. Please try again."
            SpotifyApi.PlayResult.Refused -> "Spotify refused to play. Check that Premium is active."
            is SpotifyApi.PlayResult.Failed -> {
                Log.w(TAG, "spotify play failed: ${played.why}")
                "Spotify didn't respond."
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

    private fun handleCall(spoken: String) {
        if (!actions.hasContactsPermission()) {
            reply("I need permission to read your contacts.")
            return
        }
        when (val match = NameMatcher.match(spoken, actions.loadContacts())) {
            is MatchResult.Single -> {
                val who = match.candidate
                reply("Calling ${who.name}. Tap Talk to cancel.") {
                    if (!actions.placeCall(who.number)) Log.w(TAG, "call to ${who.name} could not be placed")
                }
            }
            is MatchResult.Ambiguous ->
                reply("I found ${match.candidates.joinToString(" and ") { it.name }}. Please say the full name.")
            MatchResult.None -> reply("I couldn't find $spoken in your contacts.")
        }
    }

    private fun handleSms(spoken: String, body: String?) {
        if (!actions.hasContactsPermission()) {
            reply("I need permission to read your contacts.")
            return
        }
        if (!actions.hasSmsPermission()) {
            Log.w(TAG, "sms: permission missing")
            reply("I need permission to send text messages. Open Permissions in DriveBuddy and turn it on.")
            return
        }
        val contacts = actions.loadContacts()
        var message = body
        var match = NameMatcher.match(spoken, contacts)
        if (match == MatchResult.None && body == null) {
            // "text papa hello": there was no "saying", so the words after the contact's name are the message.
            val words = spoken.split(" ").filter { it.isNotEmpty() }
            for (k in words.size - 1 downTo 1) {
                val tryName = words.take(k).joinToString(" ")
                val m = NameMatcher.match(tryName, contacts)
                if (m != MatchResult.None) {
                    match = m
                    message = words.drop(k).joinToString(" ")
                    Log.i(TAG, "sms: read \"$tryName\" as the name and the rest as the message")
                    break
                }
            }
        }
        when (val found = match) {
            is MatchResult.Single -> {
                val who = found.candidate
                if (message.isNullOrBlank()) {
                    ask("What should I say to ${who.name}?") { answer ->
                        if (answer.isBlank()) reply("I didn't catch the message.") else confirmSms(who, SmsText.tidy(answer))
                    }
                } else {
                    confirmSms(who, SmsText.tidy(message))
                }
            }
            is MatchResult.Ambiguous ->
                reply("I found ${found.candidates.joinToString(" and ") { it.name }}. Please say the full name.")
            MatchResult.None -> reply("I couldn't find $spoken in your contacts.")
        }
    }

    private fun handleNearby(what: String) {
        Log.i(TAG, "nearby: asked for '$what'")
        if (!locationReady("nearby")) return
        main.removeCallbacks(watchdog) // location + network can outlast the watchdog
        publish("Looking for $what...")
        LocationHelper.current(this) { location ->
            if (location == null) {
                reply("I couldn't find your location.")
                return@current
            }
            thread(name = "nearby") {
                val places = NearbyClient.search(what, location.latitude, location.longitude)
                main.post {
                    when {
                        places == null -> reply("I couldn't search for places right now.")
                        places.isEmpty() -> reply("I couldn't find any $what nearby.")
                        else -> offerBatch(what, NearbyText.rank(places, location.latitude, location.longitude, limit = 20), 0)                    }
                }
            }
        }
    }

    /** Shows and reads out places [start]..[start]+2, then waits for "one", "two", "three", "more" or "cancel". */
    private fun offerBatch(what: String, ranked: List<RankedPlace>, start: Int) {
        val batch = ranked.drop(start).take(NearbyText.BATCH)
        if (batch.isEmpty()) {
            Log.i(TAG, "nearby: no more results for '$what'")
            DriveBus.showChoices(null)
            reply("That's all I found for $what nearby.")
            return
        }
        val more = start + batch.size < ranked.size
        Log.i(TAG, "nearby: offering results ${start + 1}-${start + batch.size} of ${ranked.size}")
        DriveBus.showChoices(
            ChoiceCard(
                title = what.replaceFirstChar { it.uppercase() },
                rows = batch.map { ChoiceRow(NearbyText.shortName(it.place.name), NearbyText.shortDistance(it.meters)) },
                hint = NearbyText.hint(batch.size),
                hasMore = more,
            ),
        )
        DriveBus.choiceTap = { token -> onChoiceTapped(token) }
        bringScreenToFront()
        askBatch(what, ranked, start, batch, more, NearbyText.offerBatch(batch, start == 0, more), retried = false)
    }

    private fun askBatch(
        what: String, ranked: List<RankedPlace>, start: Int, batch: List<RankedPlace>,
        more: Boolean, prompt: String, retried: Boolean,
    ) {
        ask(prompt) { raw ->
            val tapped = raw.startsWith("tap:")
            val choice = PlaceChoice.parse(raw.removePrefix("tap:"), batch.size)
            Log.i(TAG, "nearby: answer -> $choice (tapped=$tapped)")
            when (choice) {
                is PlaceChoice.Pick -> {
                    val r = batch[choice.number - 1]
                    val go = {
                        startNearbyNavigation(
                            NearbyText.shortName(r.place.name),
                            "${r.place.latitude},${r.place.longitude}",
                        )
                    }
                    if (tapped) {
                        Log.i(TAG, "nearby: navigating to #${choice.number} straight away (tapped)")
                        go()
                        finishInteraction()
                    } else {
                        reply("Starting navigation to ${NearbyText.shortName(r.place.name)}.") { go() }
                    }
                }
                PlaceChoice.More -> offerBatch(what, ranked, start + batch.size)
                PlaceChoice.Cancel -> if (tapped) finishInteraction() else reply("Okay.")
                PlaceChoice.Unknown ->
                    if (!retried) {
                        askBatch(what, ranked, start, batch, more, NearbyText.retryPrompt(batch.size, more), true)
                    } else {
                        reply("Okay, cancelled.")
                    }
            }
        }
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

    private fun handleWeather() {
        Log.i("DriveBuddy", "weather: asked")
        if (!locationReady("weather")) return
        main.removeCallbacks(watchdog) // location + network can outlast the watchdog
        publish("Checking the weather...")
        LocationHelper.current(this) { location ->
            if (location == null) {
                reply("I couldn't find your location.")
                return@current
            }
            thread(name = "weather") {
                val weather = WeatherClient.fetch(location.latitude, location.longitude)
                main.post {
                    reply(if (weather == null) "I couldn't get the weather right now." else WeatherText.speak(weather))
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

    private fun confirmSms(who: Candidate, body: String) {
        Log.i(TAG, "sms: confirming a ${body.length}-character message to ${who.name}")
        askYesNo("Send to ${who.name}: $body. Shall I send it?", onYes = {
            main.removeCallbacks(watchdog) // the phone's report can take a few seconds; we have our own 20 s limit
            publish("Sending...", DriveState.THINKING)
            actions.sendSms(who.number, body) { result ->
                reply(
                    when (result) {
                        PhoneActions.SmsResult.SENT -> "Message sent to ${who.name}."
                        PhoneActions.SmsResult.FAILED -> "I couldn't send that message."
                        PhoneActions.SmsResult.UNCONFIRMED ->
                            "I couldn't confirm that the message was sent. Please check your messages."
                    }
                )
            }
        })
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

    private fun reply(text: String, afterSpeaking: (() -> Unit)? = null) {
        main.removeCallbacks(watchdog)
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
        )
    }

    /** Says [prompt], then listens for the answer and hands the text to [onAnswer]. */
    private fun ask(prompt: String, onAnswer: (String) -> Unit) {
        Log.i(TAG, "follow-up: asking \"$prompt\"")
        pendingAsk = Ask(prompt, onAnswer)
        reply(prompt)
    }

    /** A yes/no question. A mumbled answer is asked once more, then treated as no. */
    private fun askYesNo(
        prompt: String,
        onYes: () -> Unit,
        onNo: () -> Unit = { reply("Okay, cancelled.") },
        retried: Boolean = false,
    ) {
        ask(prompt) { text ->
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
        Log.i(TAG, "follow-up: listening for an answer to \"${asked.prompt}\"")
        cue.listening()
        publish("Listening...", DriveState.LISTENING)
        main.postDelayed(watchdog, INTERACTION_TIMEOUT_MS)
        main.postDelayed({ if (!destroyed) startCapture() }, 150)
    }

    private fun finishInteraction() {
        main.removeCallbacks(watchdog)
        speaking = false
        pendingAsk = null
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
        val channel = NotificationChannel(CHANNEL_ID, "Drive session", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
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
        val icon = android.R.drawable.ic_btn_speak_now
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
