package app.drivebuddy

import android.content.Context
import java.io.File

/** Appends one JSON line per interaction to the app's external files dir (pull it with adb). */
class LatencyLog(context: Context) {
    private val dir: File? = context.getExternalFilesDir(null)
    val file: File? get() = dir?.let { File(it, "latency.jsonl") }

    /** Last [n] lines, for showing on screen without needing adb. */
    fun tail(n: Int): String = try {
        file?.takeIf { it.exists() }?.readLines()?.takeLast(n)?.joinToString("\n") ?: "(no log yet)"
    } catch (e: java.io.IOException) {
        "(could not read log)"
    }

    fun append(jsonLine: String) {
        try {
            file?.appendText(jsonLine + "\n")
        } catch (e: java.io.IOException) {
            // Logging must never break a drive interaction.
        }
    }
}
