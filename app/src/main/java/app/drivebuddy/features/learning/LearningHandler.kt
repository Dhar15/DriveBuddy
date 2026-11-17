package app.drivebuddy.features.learning

import android.util.Log
import app.drivebuddy.service.Conversation
import app.drivebuddy.ui.ChoiceCard
import app.drivebuddy.ui.DriveState
import app.drivebuddy.ui.LearningCard
import java.time.LocalDate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The word and the GK fact of the day, read out. Used as steps of the commute; [prefetch] gets them ready early. */
class LearningHandler(private val c: Conversation) {

    private companion object {
        const val TAG = "DriveBuddy"
        const val WAIT_SECONDS = 15L // how long a step waits for a fetch that is still running
    }

    private val cache by lazy { LearningCache(c.context) }
    private var wordJob: CompletableFuture<WordOfDay?>? = null
    private var gkJob: CompletableFuture<GkFact?>? = null
    private var jobsDay: LocalDate? = null

    /** Starts fetching both items in the background, so they are ready by the time they are spoken. */
    fun prefetch() = startJobs()

    private fun startJobs() {
        val today = LocalDate.now()
        if (jobsDay != today) { wordJob = null; gkJob = null; jobsDay = today }
        val day = today.toString()
        if (needsFetch(wordJob)) {
            Log.i(TAG, "word: preloading")
            wordJob = CompletableFuture.supplyAsync { runCatching { loadWord(day) }.getOrNull() }
        }
        if (needsFetch(gkJob)) {
            Log.i(TAG, "gk: preloading")
            gkJob = CompletableFuture.supplyAsync { runCatching { loadGk(day) }.getOrNull() }
        }
    }

    /** Nothing started yet, or the last try failed. A fetch that is still running is left alone. */
    private fun <T> needsFetch(job: CompletableFuture<T?>?): Boolean =
        job == null || (job.isDone && job.getNow(null) == null)

    private fun loadWord(day: String): WordOfDay? {
        cache.word(day)?.let { Log.i(TAG, "word: from today's saved copy"); return it }
        for (attempt in 1..2) {
            Log.i(TAG, "word: fetching (attempt $attempt)")
            WordOfDayClient.fetch(readTimeoutMs = 25_000)?.let { cache.saveWord(day, it); return it }
        }
        return null
    }

    private fun loadGk(day: String): GkFact? {
        cache.gk(day)?.let { Log.i(TAG, "gk: from today's saved copy"); return it }
        for (attempt in 1..2) {
            Log.i(TAG, "gk: fetching (attempt $attempt)")
            GkClient.fetch()?.let { cache.saveGk(day, it); return it }
        }
        return null
    }

    private fun <T> await(job: CompletableFuture<T?>, what: String): T? = try {
        job.get(WAIT_SECONDS, TimeUnit.SECONDS)
    } catch (e: Exception) {
        Log.w(TAG, "$what: not ready after ${WAIT_SECONDS}s (${e.javaClass.simpleName})")
        null
    }

    fun word() {
        Log.i(TAG, "word: asked")
        c.pauseWatchdog()
        c.publish("Getting the word of the day...", DriveState.THINKING)
        startJobs()
        val job = wordJob!!
        Log.i(TAG, "word: ${if (job.isDone) "already loaded" else "still loading, waiting"}")
        thread(name = "word-wait") {
            val word = await(job, "word")
            c.post {
                if (c.destroyed) return@post
                if (word == null) c.reply("I couldn't get the word of the day.")
                else {
                    c.showCards(ChoiceCard(
                        title = "Word of the day", rows = emptyList(), hint = "", hasMore = false,
                        learning = LearningCard(
                            kicker = "Word of the day", tag = "", headline = word.word.trim(),
                            sections = listOfNotNull(
                                "Meaning" to word.meaning.trim(),
                                word.example.takeIf { it.isNotBlank() }?.let { "Example" to it.trim() },
                            ),
                        ),
                    ))
                    c.reply(LearningText.word(word.word, word.meaning, word.example))
                }
            }
        }
    }

    fun gk() {
        Log.i(TAG, "gk: asked")
        c.pauseWatchdog()
        c.publish("Getting the G K of the day...", DriveState.THINKING)
        startJobs()
        val job = gkJob!!
        Log.i(TAG, "gk: ${if (job.isDone) "already loaded" else "still loading, waiting"}")
        thread(name = "gk-wait") {
            val fact = await(job, "gk")
            c.post {
                if (c.destroyed) return@post
                if (fact == null) c.reply("I couldn't get the G K of the day.")
                else {
                    c.showCards(ChoiceCard(
                        title = "GK of the day", rows = emptyList(), hint = "", hasMore = false,
                        learning = LearningCard(
                            kicker = "GK of the day", tag = fact.category.trim(),
                            headline = fact.fact.trim(), sections = emptyList(),
                        ),
                    ))
                    c.reply(LearningText.gk(fact.category, fact.fact))
                }
            }
        }
    }
}