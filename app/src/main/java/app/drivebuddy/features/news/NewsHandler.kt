package app.drivebuddy.features.news

import android.content.Intent
import android.net.Uri
import android.util.Log
import app.drivebuddy.features.nearby.PlaceChoice
import app.drivebuddy.service.Conversation
import app.drivebuddy.ui.ChoiceCard
import app.drivebuddy.ui.ChoiceRow
import app.drivebuddy.ui.DriveBus
import app.drivebuddy.ui.DriveState
import kotlin.concurrent.thread

/** "Tell me the news": fetches fresh headlines, shows them as cards, reads them out, opens the one you pick. */
class NewsHandler(private val c: Conversation) {

    private companion object { const val TAG = "DriveBuddy" }

    private val seen by lazy { NewsSeen(c.context) }

    fun handle(world: Boolean) {
        Log.i(TAG, "news: asked (world=$world)")
        c.pauseWatchdog()
        c.publish("Getting the news...", DriveState.THINKING)
        thread(name = "news") {
            val all = NewsClient.headlines(if (world) NewsClient.WORLD else NewsClient.INDIA)
            c.post {
                if (c.destroyed) return@post
                if (all == null) {
                    c.reply("I couldn't get the news right now.")
                    return@post
                }
                val fresh = NewsPicker.fresh(all, seen.links())
                Log.i(TAG, "news: ${all.size} in the feed, ${fresh.size} not heard before")
                when {
                    all.isEmpty() -> c.reply("I couldn't find any headlines.")
                    fresh.isEmpty() -> c.reply("There's nothing new since I last checked.")
                    else -> offer(if (world) "the BBC" else "The Hindu", fresh, 0)
                }
            }
        }
    }

    /** Shows up to five cards, reads them out, then waits for a number, "more" or "done". */
    private fun offer(source: String, fresh: List<Headline>, start: Int) {
        val batch = fresh.drop(start).take(NewsText.BATCH)
        if (batch.isEmpty()) {
            DriveBus.showChoices(null)
            c.reply("That's all the new headlines I have.")
            return
        }
        seen.mark(batch)
        val more = start + batch.size < fresh.size
        Log.i(TAG, "news: showing ${start + 1}-${start + batch.size} of ${fresh.size} new")
        c.showCards(
            ChoiceCard(
                title = "News",
                rows = batch.map { ChoiceRow(it.title, source, NewsText.tile(it.summary, 120)) },
                hint = "Swipe for the next story, or say its number",
                hasMore = more,
                paged = true,
                action = "Read story",
            ),
        )
        val tail = if (more) " Say a number to open one, more, or done." else " Say a number to open one, or done."
        val spoken = NewsText.speak(batch.map { it.title }, start == 0, source) + tail
        c.ask(spoken, onProgress = c.slideAlong(spoken, batch.indices.map { "Number ${it + 1}." })) { raw ->
            val tapped = raw.startsWith("tap:")
            when (val choice = StoryChoice.parse(raw.removePrefix("tap:"), batch.size)) {
                is PlaceChoice.Pick -> {
                    val story = batch[choice.number - 1]
                    Log.i(TAG, "news: opening story #${choice.number} (tapped=$tapped)")
                    if (tapped) {
                        openLink(story.link)
                        c.finish()
                    } else {
                        c.reply("Opening it on your phone.") { openLink(story.link) }
                    }
                }
                PlaceChoice.More -> offer(source, fresh, start + batch.size)
                else -> if (tapped) c.finish() else c.reply("Okay.")
            }
        }
    }

    private fun openLink(url: String) {
        try {
            c.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i(TAG, "news: opened an article")
        } catch (e: Exception) {
            Log.w(TAG, "news: could not open the article: ${e.javaClass.simpleName}")
        }
    }
}