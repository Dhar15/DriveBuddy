package app.drivebuddy.features.nearby

import android.util.Log
import app.drivebuddy.common.LocationHelper
import app.drivebuddy.service.Conversation
import app.drivebuddy.ui.ChoiceCard
import app.drivebuddy.ui.ChoiceRow
import app.drivebuddy.ui.DriveBus
import kotlin.concurrent.thread

/** "Find a petrol pump nearby": searches, shows cards, reads them out, starts navigation to the one you pick. */
class NearbyHandler(private val c: Conversation) {

    private companion object { const val TAG = "DriveBuddy" }

    fun handle(what: String) {
        Log.i(TAG, "nearby: asked for '$what'")
        if (!c.locationReady("nearby")) return
        c.pauseWatchdog() // location + network can outlast the watchdog
        c.publish("Looking for $what...")
        LocationHelper.current(c.context) { location ->
            if (location == null) {
                c.reply("I couldn't find your location.")
                return@current
            }
            thread(name = "nearby") {
                val places = NearbyClient.search(what, location.latitude, location.longitude)
                c.post {
                    when {
                        places == null -> c.reply("I couldn't search for places right now.")
                        places.isEmpty() -> c.reply("I couldn't find any $what nearby.")
                        else -> offer(
                            what,
                            NearbyText.rank(places, location.latitude, location.longitude, limit = 20),
                            0,
                        )
                    }
                }
            }
        }
    }

    /** Shows and reads out the places from [start], then waits for a number, "more" or "cancel". */
    private fun offer(what: String, ranked: List<RankedPlace>, start: Int) {
        val batch = ranked.drop(start).take(NearbyText.BATCH)
        if (batch.isEmpty()) {
            Log.i(TAG, "nearby: no more results for '$what'")
            DriveBus.showChoices(null)
            c.reply("That's all I found for $what nearby.")
            return
        }
        val more = start + batch.size < ranked.size
        Log.i(TAG, "nearby: offering results ${start + 1}-${start + batch.size} of ${ranked.size}")
        c.showCards(
            ChoiceCard(
                title = what.replaceFirstChar { it.uppercase() },
                rows = batch.map {
                    ChoiceRow(
                        NearbyText.shortName(it.place.name),
                        NearbyText.shortDistance(it.meters),
                        TripEstimate.byCar(it.meters.toDouble()),
                    )
                },
                hint = NearbyText.hint(batch.size),
                hasMore = more,
                paged = true,
                compact = true,
            ),
        )
        val prompt = NearbyText.offerBatch(batch, start == 0, more)
        askBatch(
            what, ranked, start, batch, more, prompt, retried = false,
            onProgress = c.slideAlong(prompt, batch.map { NearbyText.shortName(it.place.name) }),
        )
    }

    private fun askBatch(
        what: String, ranked: List<RankedPlace>, start: Int, batch: List<RankedPlace>,
        more: Boolean, prompt: String, retried: Boolean, onProgress: ((Int) -> Unit)? = null,
    ) {
        c.ask(prompt, onProgress) { raw ->
            val tapped = raw.startsWith("tap:")
            val choice = PlaceChoice.parse(raw.removePrefix("tap:"), batch.size)
            Log.i(TAG, "nearby: answer -> $choice (tapped=$tapped)")
            when (choice) {
                is PlaceChoice.Pick -> {
                    val r = batch[choice.number - 1]
                    val name = NearbyText.shortName(r.place.name)
                    val go = { c.startNavigation(name, "${r.place.latitude},${r.place.longitude}") }
                    if (tapped) {
                        Log.i(TAG, "nearby: navigating to #${choice.number} straight away (tapped)")
                        go()
                        c.finish()
                    } else {
                        c.reply("Starting navigation to $name.") { go() }
                    }
                }
                PlaceChoice.More -> offer(what, ranked, start + batch.size)
                PlaceChoice.Cancel -> if (tapped) c.finish() else c.reply("Okay.")
                PlaceChoice.Unknown ->
                    if (!retried) {
                        askBatch(what, ranked, start, batch, more, NearbyText.retryPrompt(batch.size, more), true)
                    } else {
                        c.reply("Okay, cancelled.")
                    }
            }
        }
    }
}