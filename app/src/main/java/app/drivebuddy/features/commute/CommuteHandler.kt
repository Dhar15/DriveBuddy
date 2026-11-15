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
import app.drivebuddy.service.Conversation
import app.drivebuddy.ui.DriveState
import kotlin.concurrent.thread

/** "Let's commute": weather, then the top news, then navigation to the other of home/work, then music. */
class CommuteHandler(
    private val c: Conversation,
    private val weather: WeatherHandler,
    private val news: NewsHandler,
    private val navigation: NavigationHandler,
    private val music: MusicHandler,
) {

    private companion object {
        const val TAG = "DriveBuddy"
        const val HOME = "home"
        const val WORK = "work" // change to "office" if that is your workplace's saved name
    }

    private val quota by lazy { EtaQuota(c.context) }

    private val steps: List<Pair<String, () -> Unit>> = listOf(
        "welcome" to { c.reply("Welcome back. Initiating your commute.") },
        "weather" to { weather.handle() },
        "news" to { news.handle(world = false, brief = true) },
        "navigation" to { navigationStep() },
        "music" to { musicStep() },
    )

    fun start() {
        Log.i(TAG, "commute: starting, ${steps.size} steps")
        c.pauseWatchdog()
        run(0)
    }

    private fun run(index: Int) {
        val (name, action) = steps[index]
        Log.i(TAG, "commute: step ${index + 1} of ${steps.size}: $name")
        if (index < steps.size - 1) c.continueWith { run(index + 1) }
        action()
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