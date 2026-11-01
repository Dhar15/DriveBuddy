package app.drivebuddy

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import android.content.Intent
import android.provider.Settings

/** Finds the phone's current location with the platform API (no Google libraries). Result arrives on the main thread. */
object LocationHelper {
    private const val TAG = "DriveBuddy"
    private const val FRESH_MS = 10 * 60_000L
    private const val WAIT_MS = 8_000L

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isLocationOn(context: Context): Boolean =
        (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled

    /** Opens the phone's location switch screen. */
    fun openSettings(context: Context): Boolean = try {
        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Log.i(TAG, "location: opened settings")
        true
    } catch (e: Exception) {
        Log.w(TAG, "location: could not open settings: ${e.javaClass.simpleName}")
        false
    }

    @SuppressLint("MissingPermission")
    fun current(context: Context, onResult: (Location?) -> Unit) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        Log.i(TAG, "location: enabled providers=$providers")
        if (providers.isEmpty()) {
            Log.w(TAG, "location: no provider enabled (location services off?)")
            onResult(null)
            return
        }

        val last = try {
            providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (e: SecurityException) {
            Log.w(TAG, "location: permission refused for last known: ${e.message}")
            onResult(null)
            return
        }
        val ageMs = last?.let { System.currentTimeMillis() - it.time }
        Log.i(TAG, "location: last known age=${ageMs?.div(1000)}s")
        if (last != null && ageMs != null && ageMs < FRESH_MS) {
            onResult(last)
            return
        }
        if (Build.VERSION.SDK_INT < 30) {
            onResult(last)
            return
        }

        val handler = Handler(Looper.getMainLooper())
        val done = AtomicBoolean(false)
        fun finish(loc: Location?) { if (done.compareAndSet(false, true)) onResult(loc) }
        val signal = CancellationSignal()
        val timeout = Runnable {
            Log.w(TAG, "location: no fresh fix within ${WAIT_MS / 1000}s, using last known")
            signal.cancel()
            finish(last)
        }
        handler.postDelayed(timeout, WAIT_MS)
        try {
            lm.getCurrentLocation(providers.first(), signal, context.mainExecutor) { loc ->
                handler.removeCallbacks(timeout)
                Log.i(TAG, "location: fresh fix ${if (loc != null) "received" else "failed"}")
                finish(loc ?: last)
            }
        } catch (e: SecurityException) {
            handler.removeCallbacks(timeout)
            Log.w(TAG, "location: permission refused: ${e.message}")
            finish(last)
        }
    }
}