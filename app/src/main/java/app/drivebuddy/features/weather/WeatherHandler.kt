package app.drivebuddy.features.weather

import android.util.Log
import app.drivebuddy.common.LocationHelper
import app.drivebuddy.service.Conversation
import kotlin.concurrent.thread
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import app.drivebuddy.ui.ChoiceCard
import app.drivebuddy.ui.WeatherCard
import app.drivebuddy.ui.DriveState

/** "What's the weather": finds the location, fetches the forecast, reads it out. */
class WeatherHandler(private val c: Conversation) {

    private companion object { const val TAG = "DriveBuddy" }

    fun handle() {
        Log.i(TAG, "weather: asked")
        if (!c.locationReady("weather")) return
        c.pauseWatchdog() // location + network can outlast the watchdog
        c.publish("Checking the weather...", DriveState.THINKING)
        LocationHelper.current(c.context) { location ->
            if (location == null) {
                c.reply("I couldn't find your location.")
                return@current
            }
            thread(name = "weather") {
                // The place name is looked up alongside the forecast and waited for briefly, so it never slows the answer much.
                val place = AtomicReference<String?>(null)
                val geo = thread(name = "weather-place") {
                    place.set(PlaceName.of(c.context, location.latitude, location.longitude))
                }
                val weather = WeatherClient.fetch(location.latitude, location.longitude)
                geo.join(1_500)
                c.post {
                    if (c.destroyed) return@post
                    if (weather == null) {
                        c.reply("I couldn't get the weather right now.")
                        return@post
                    }
                    c.showCards(card(weather, place.get()))
                    c.reply(WeatherText.speak(weather))
                }
            }
        }
    }

    private fun card(w: WeatherNow, place: String?): ChoiceCard {
        val day = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date())
        return ChoiceCard(
            title = "Weather", rows = emptyList(), hint = "", hasMore = false,
            weather = WeatherCard(
                title = if (place == null) day else "$day · $place",
                tempC = w.tempC.roundToInt(),
                condition = WeatherText.describe(w.code).orEmpty(),
                detail = WeatherText.detail(w),
                isDay = w.isDay,
                code = w.code,
                hours = w.hours,
            ),
        )
    }
}