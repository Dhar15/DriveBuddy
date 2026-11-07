package app.drivebuddy

import android.util.Log
import android.util.Xml
import app.drivebuddy.core.NewsText
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import app.drivebuddy.core.Headline

/** Headlines from a public RSS feed. Blocking: call it off the main thread. */
object NewsClient {
    const val INDIA = "https://www.thehindu.com/news/national/feeder/default.rss"
    const val WORLD = "https://feeds.bbci.co.uk/news/world/rss.xml"
    private const val TAG = "DriveBuddy"
    private const val MAX_TITLES = 30

    /** Headline titles, or null if the feed could not be fetched or read. */
    fun headlines(feedUrl: String): List<Headline>? {
        val started = System.currentTimeMillis()
        val conn = URL(feedUrl).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", "DriveBuddy/1.0")
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "news: http $code")
                return null
            }
            val items = conn.inputStream.use { parse(it) }
            Log.i(TAG, "news: ${items.size} headlines in ${System.currentTimeMillis() - started}ms")
            items
        } catch (e: Exception) {
            Log.w(TAG, "news: failed: ${e.javaClass.simpleName}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(stream: InputStream): List<Headline> {
        val parser = Xml.newPullParser()
        parser.setInput(stream, null)
        val out = mutableListOf<Headline>()
        var inItem = false
        var title = ""
        var link = ""
        var summary = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && out.size < MAX_TITLES) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "item" -> { inItem = true; title = ""; link = ""; summary = "" }
                    "title" -> if (inItem) title = NewsText.clean(parser.nextText())
                    "link" -> if (inItem) link = parser.nextText().trim()
                    "description" -> if (inItem) summary = NewsText.clean(parser.nextText())
                }
                XmlPullParser.END_TAG -> if (parser.name == "item") {
                    inItem = false
                    if (title.isNotEmpty() && link.isNotEmpty()) out += Headline(title, link.replace("http://", "https://"), if (summary == title) "" else summary)
                }
            }
            event = parser.next()
        }
        return out
    }
}