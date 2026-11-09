package app.drivebuddy.features.weather

import android.util.Log
import app.drivebuddy.common.LocationHelper
import app.drivebuddy.service.Conversation
import kotlin.concurrent.thread

/** "What's the weather": finds the location, fetches the forecast, reads it out. */
class WeatherHandler(private val c: Conversation) {

    private companion object { const val TAG = "DriveBuddy" }

    fun handle() {
        Log.i(TAG, "weather: asked")
        if (!c.locationReady("weather")) return
        c.pauseWatchdog() // location + network can outlast the watchdog
        c.publish("Checking the weather...")
        LocationHelper.current(c.context) { location ->
            if (location == null) {
                c.reply("I couldn't find your location.")
                return@current
            }
            thread(name = "weather") {
                val weather = WeatherClient.fetch(location.latitude, location.longitude)
                c.post {
                    c.reply(if (weather == null) "I couldn't get the weather right now." else WeatherText.speak(weather))
                }
            }
        }
    }
}