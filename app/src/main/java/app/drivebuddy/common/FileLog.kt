package app.drivebuddy.common

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Copies this app's own logcat lines into one file per day (kept for [KEEP_DAYS] days),
 * so a problem can be investigated after the fact without adb.
 * Existing Log calls are unchanged; nothing here can break an interaction.
 */
object FileLog {
    private const val TAG = "DriveBuddy"
    private const val KEEP_DAYS = 7
    private const val RESTART_DELAY_MS = 2_000L
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Volatile private var started = false
    @Volatile private var dir: File? = null

    fun start(context: Context) {
        if (started) return
        started = true
        val d = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
        d.mkdirs()
        dir = d
        pruneOld(d)
        writeSessionBanner(d)
        Thread({ capture(d) }, "file-log").apply { isDaemon = true }.start()
        logLastExits(context)
        Log.i(TAG, "FileLog started: dir=${d.name} keepDays=$KEEP_DAYS")
    }

    /** Newest first. Used by the log viewer. */
    fun files(): List<File> =
        dir?.listFiles { f -> f.name.startsWith("drivebuddy-") }
            ?.sortedByDescending { it.name } ?: emptyList()

    private fun capture(d: File) {
        var first = true
        while (true) {
            try {
                val cmd = mutableListOf(
                    "logcat", "-v", "threadtime", "--pid=${android.os.Process.myPid()}"
                )
                if (!first) cmd += listOf("-T", "1") // after a restart, skip the replayed history
                first = false
                val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
                var day = ""
                var out: BufferedWriter? = null
                try {
                    proc.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            val today = dayFormat.format(Date())
                            if (today != day) {
                                out?.close()
                                out = BufferedWriter(FileWriter(File(d, "drivebuddy-$today.log"), true))
                                day = today
                            }
                            out?.apply { write(line); newLine(); flush() }
                        }
                    }
                } finally {
                    out?.close()
                    proc.destroy()
                }
            } catch (e: Exception) {
                Log.w(TAG, "FileLog capture stopped: ${e.javaClass.simpleName}")
            }
            try { Thread.sleep(RESTART_DELAY_MS) } catch (_: InterruptedException) { return }
        }
    }

    private fun pruneOld(d: File) {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600 * 1000
        d.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    /** A visible break so separate runs of the app are easy to tell apart in the file. */
    private fun writeSessionBanner(d: File) {
        try {
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val f = File(d, "drivebuddy-${dayFormat.format(Date())}.log")
            FileWriter(f, true).use {
                it.write("\n========== NEW SESSION $now pid=${android.os.Process.myPid()} ==========\n\n")
            }
        } catch (_: Exception) {
        }
    }

    /** Folder of the log files (also where exported files for sharing are written). */
    fun dir(): File? = dir

    /** Android remembers why recent processes ended (crash, ANR, killed for memory...). */
    private fun logLastExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = context.getSystemService(ActivityManager::class.java)
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            am.getHistoricalProcessExitReasons(null, 0, 5).forEach { e ->
                Log.i(
                    TAG,
                    "Previous process exit: at=${fmt.format(Date(e.timestamp))} " +
                            "reason=${reasonName(e.reason)} importance=${e.importance} " +
                            "status=${e.status} desc=${e.description}"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read exit history: ${e.javaClass.simpleName}")
        }
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED(killed)"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED(force stop)"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        else -> "OTHER($r)"
    }
}