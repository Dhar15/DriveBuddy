package app.drivebuddy.features.commute

import android.util.Log
import app.drivebuddy.common.LocationHelper
import app.drivebuddy.features.music.MusicHandler
import app.drivebuddy.features.navigation.EtaClient
import app.drivebuddy.features.navigation.EtaQuota
import app.drivebuddy.features.navigation.NavigationHandler
import app.drivebuddy.features.navigation.SavedPlaces
import app.drivebuddy.features.news.NewsHandler
import app.drivebuddy.features.weather.WeatherHandler
import app.drivebuddy.features.learning.LearningHandler
import app.drivebuddy.service.Conversation
import app.drivebuddy.ui.DriveState
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.os.SystemClock
import kotlin.concurrent.thread

/** "Let's commute": weather, then the top news, then navigation to the other of home/work, then music. */
class CommuteHandler(
    private val c: Conversation,
    private val weather: WeatherHandler,
    private val news: NewsHandler,
    private val learning: LearningHandler,
    private val navigation: NavigationHandler,
    private val music: MusicHandler,
) {

    private companion object {
        const val TAG = "DriveBuddy"
        const val HOME = "home"
        const val WORK = "work" // change to "office" if that is your workplace's saved name
    }

    private val quota by lazy { EtaQuota(c.context) }

    private val main = Handler(Looper.getMainLooper())
    private var current = 0 // the step being run, so a step can say where to carry on

    private val steps: List<Pair<String, () -> Unit>> = listOf(
        "welcome" to { c.reply("Welcome back. Starting your commute.") },
        "weather" to { weatherStep() },
        "news" to { news.handle(world = false, brief = true) },
        "word" to { learning.word() },
        "gk" to { learning.gk() },
        "navigation" to { navigationStep() },
        "music" to { musicStep() },
    )

    fun start() {
        Log.i(TAG, "commute: starting, ${steps.size} steps")
        c.pauseWatchdog()
        warmUpLocation()
        learning.prefetch()
        run(0)
    }

    private fun run(index: Int) {
        current = index
        val (name, action) = steps[index]
        Log.i(TAG, "commute: step ${index + 1} of ${steps.size}: $name")
        if (index < steps.size - 1) c.continueWith { run(index + 1) }
        action()
    }

    /** Finds a fresh position while the welcome is spoken, so the weather and navigation steps find one ready. */
    private fun warmUpLocation() {
        val ctx = c.context
        if (!LocationHelper.hasPermission(ctx) || !LocationHelper.isLocationOn(ctx)) return
        val started = SystemClock.elapsedRealtime()
        LocationHelper.current(ctx) { fix ->
            Log.i(TAG, "commute: location warm-up ${if (fix != null) "ready" else "found nothing"} in ${SystemClock.elapsedRealtime() - started}ms")
        }
    }

    // --- Step 2: weather, which needs location ---------------------------------------------

    private fun weatherStep() {
        val ctx = c.context
        // Permission problems keep the normal weather flow; this only handles "location switched off".
        if (!LocationHelper.hasPermission(ctx) || LocationHelper.isLocationOn(ctx)) {
            weather.handle()
            return
        }
        val after = current + 1
        Log.i(TAG, "commute: location is off, offering to open the settings")
        c.askYesNo("Your phone's location is off. Should I open the settings so I can tell you the weather?") {
            c.reply("Opening location settings. Turn location on and I'll carry on.", afterSpeaking = {
                try {
                    ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    Log.i(TAG, "commute: opened location settings")
                } catch (e: Exception) {
                    Log.w(TAG, "commute: could not open location settings: ${e.javaClass.simpleName}")
                }
                // The reply finishing would normally move on to the next step; wait for location instead.
                c.continueWith { waitForLocation(after, System.currentTimeMillis()) }
            })
        }
    }

    /** Checks once a second for up to 90 seconds; then does the weather, or gives it up, and carries on. */
    private fun waitForLocation(after: Int, startedAt: Long) {
        if (c.destroyed) return
        c.pauseWatchdog()
        c.publish("Waiting for location...", DriveState.THINKING)
        when {
            LocationHelper.isLocationOn(c.context) -> {
                Log.i(TAG, "commute: location is on, finding you before the weather")
                c.publish("Finding your location...", DriveState.THINKING)
                // A phone that has only just had location switched on is often slow to give a first position,
                // so give it one full try here before the weather step makes its own.
                LocationHelper.current(c.context) { fix ->
                    Log.i(TAG, "commute: after settings, ${if (fix != null) "got a position" else "no position yet"}")
                    if (!c.destroyed) {
                        c.continueWith { run(after) }
                        weather.handle()
                    }
                }
            }
            System.currentTimeMillis() - startedAt > 90_000 -> {
                Log.i(TAG, "commute: location still off after 90 s, skipping the weather")
                c.continueWith { run(after) }
                c.reply("I didn't see location turned on, so I'm skipping the weather.")
            }
            else -> main.postDelayed({ waitForLocation(after, startedAt) }, 1_000)
        }
    }

    // --- Step 3: where to ----------------------------------------------------------------

    private fun navigationStep() {
        val saved = SavedPlaces.all(c.context).map { it.name }
        val hasBoth = saved.any { it.equals(HOME, true) } && saved.any { it.equals(WORK, true) }
        val canLookUp = hasBoth && quota.canUse() &&
                LocationHelper.hasPermission(c.context) && LocationHelper.isLocationOn(c.context)
        if (!canLookUp) {
            Log.i(TAG, "commute: can't work out the direction (saved=$hasBoth), asking")
            askWhere(saved)
            return
        }
        c.pauseWatchdog()
        c.publish("Working out where you're headed...", DriveState.THINKING)
        LocationHelper.current(c.context) { location ->
            if (location == null) {
                Log.i(TAG, "commute: no location fix, asking")
                askWhere(saved)
                return@current
            }
            thread(name = "commute-eta") {
                quota.record()
                quota.record()
                val home = EtaClient.driveSeconds(location.latitude, location.longitude, SavedPlaces.resolve(c.context, HOME))
                val work = EtaClient.driveSeconds(location.latitude, location.longitude, SavedPlaces.resolve(c.context, WORK))
                c.post {
                    if (c.destroyed) return@post
                    val to = CommuteDestination.pick(home, work, HOME, WORK)
                    Log.i(TAG, "commute: home=${home ?: "?"}s work=${work ?: "?"}s -> ${to ?: "ask"}")
                    if (to == null) askWhere(saved) else navigation.navigate(to)
                }
            }
        }
    }

    private fun askWhere(saved: List<String>, retried: Boolean = false) {
        val prompt = if (retried) "Sorry, which place? Say home, work, or a saved place." else "Where are you headed?"
        c.ask(prompt) { answer ->
            val place = CommuteDestination.place(answer, saved)
            when {
                place != null -> navigation.navigate(place)
                !retried -> askWhere(saved, retried = true)
                else -> c.reply("Okay, skipping the navigation.")
            }
        }
    }

    // --- Step 4: music -------------------------------------------------------------------

    private fun musicStep(retried: Boolean = false) {
        val prompt =
            if (retried) "Sorry, say liked songs, playlist, or no."
            else "Shall I play your liked songs, or one of your playlists?"
        c.ask(prompt) { answer ->
            when (CommuteMusic.parse(answer)) {
                CommuteMusicChoice.LIKED -> { c.holdMusicPaused(); music.playLiked() }
                CommuteMusicChoice.PLAYLIST -> { c.holdMusicPaused(); music.playPlaylist(null) }
                CommuteMusicChoice.NONE -> c.reply("Okay, no music. Drive safe.")
                CommuteMusicChoice.UNKNOWN ->
                    if (!retried) musicStep(retried = true) else c.reply("Okay, no music. Drive safe.")
            }
        }
    }
}