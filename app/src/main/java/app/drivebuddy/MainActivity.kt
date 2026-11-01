package app.drivebuddy

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.net.Uri
import android.provider.Settings
import app.drivebuddy.spotify.SpotifyAuth
import app.drivebuddy.ui.OrbView
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import java.util.Calendar
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.Log
import android.widget.HorizontalScrollView
import app.drivebuddy.ui.SettingsIconView

/** Plain-views screen for milestone 1: start/stop the drive service and test a "Talk" interaction. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var orb: OrbView
    private lateinit var headline: TextView
    private var wanted = ""
    private lateinit var suggestions: HorizontalScrollView
    private lateinit var chips: LinearLayout
    private var chipsAnimated = false
    private lateinit var lastTrip: TextView

    private val permissionRequest = 100

    private fun refreshLog() {
        logView.text = "Last log lines:\n" + LatencyLog(this).tail(3)
    }

    companion object {
        private var autoStarted = false
        private const val TAG = "DriveBuddy"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0A0C0F.toInt())
            setPadding(pad, pad, pad, pad)
        }

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val hello = when {
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }
        val greeting = TextView(this).apply {
            text = "$hello,\nKshitij"
            textSize = 32f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setLineSpacing(0f, 1.05f)
            // Long-press shows or hides the latency log.
            setOnLongClickListener {
                logView.visibility = if (logView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                true
            }
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(greeting, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val settingsButton = SettingsIconView(this).apply {
            val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            background = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, mask)
            setOnClickListener {
                Log.i(TAG, "settings opened from home")
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
        header.addView(settingsButton, LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()))
        root.addView(header)

        lastTrip = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF8A919E.toInt())
            setPadding(0, (4 * d).toInt(), 0, 0)
            visibility = View.GONE
            setOnClickListener {
                Log.i(TAG, "journey screen opened from home")
                startActivity(Intent(this@MainActivity, JourneyActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }
        root.addView(lastTrip)

        orb = OrbView(this)
        orb.setOnClickListener {
            val state = DriveBus.current.state
            Log.i(TAG, "orb tapped (state=$state)")
            // After "End drive session" the orb restarts everything, wake word included.
            if (state == DriveState.OFF) startSession() else send(DriveService.ACTION_TALK)
        }
        root.addView(orb, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        headline = TextView(this).apply {
            text = "Tap to talk"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        root.addView(headline, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        status = TextView(this).apply {
            text = "Tap the orb to start"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFF8A919E.toInt())
            setPadding(0, pad / 3, 0, pad / 2)
        }
        root.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        suggestions = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true // a short row sits centred under the headline
            alpha = 0f            // render() fades it in only while idle
            addView(chips)
        }
        root.addView(suggestions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        logView = TextView(this).apply {
            textSize = 11f
            visibility = View.GONE
            setTextColor(0xFF6B7280.toInt())
            setTextIsSelectable(true)
        }
        root.addView(logView)

        val end = TextView(this).apply {
            text = "End session"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFFE5766A.toInt())
            setPadding((24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt(), (12 * d).toInt())
            val shape = GradientDrawable().apply {
                cornerRadius = 24 * d
                setColor(0x14D9604F)
                setStroke((1 * d).toInt(), 0x66D9604F)
            }
            val mask = GradientDrawable().apply { cornerRadius = 24 * d; setColor(Color.WHITE) }
            background = RippleDrawable(ColorStateList.valueOf(0x33D9604F), shape, mask)
            setOnClickListener {
                Log.i(TAG, "end session tapped")
                send(DriveService.ACTION_STOP)
            }
        }
        root.addView(end, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = pad / 2
        })

        // Android 15 draws edge-to-edge: keep content clear of the status and navigation bars.
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(pad, insets.systemWindowInsetTop + pad, pad, insets.systemWindowInsetBottom + pad)
            insets
        }

        setContentView(root)

        // One gentle entrance: the greeting eases in when the app opens.
        greeting.alpha = 0f
        greeting.translationY = 12 * d
        greeting.animate().alpha(1f).translationY(0f).setDuration(600).start()
    }

    /** Draws the orb and the big headline for a state; the headline cross-fades. */
    /** Draws the orb, the big headline and the small line under it for one state. */
    private fun render(ui: DriveUiState) {
        orb.setState(ui.state)
        val words = when (ui.state) {
            DriveState.OFF, DriveState.IDLE -> "Tap to talk"
            DriveState.LISTENING -> "Listening"
            DriveState.THINKING -> "One moment"
            DriveState.SPEAKING -> "Speaking"
            DriveState.ERROR -> "Something's wrong"
        }
        status.text = when (ui.state) {
            DriveState.OFF -> "Tap the orb to start"
            DriveState.IDLE -> "or say \"Hi Oreo\""
            DriveState.LISTENING -> "Go ahead"
            DriveState.THINKING, DriveState.SPEAKING -> ""
            DriveState.ERROR -> ui.detail
        }
        suggestions.animate().alpha(if (ui.state == DriveState.IDLE) 1f else 0f).setDuration(200).start()
        wanted = words
        if (headline.text.toString() == words) return
        headline.animate().alpha(0f).setDuration(100).withEndAction {
            headline.text = wanted
            headline.animate().alpha(1f).setDuration(160).start()
        }.start()
    }

    /** Rebuilds the chips from your saved places. Called every time the screen comes back, so edits show up. */
    private fun showSuggestions() {
        val d = resources.displayMetrics.density
        val places = SavedPlaces.all(this).take(4)
        Log.i(TAG, "suggestions: ${places.size} chips (${places.joinToString { it.name }})")
        chips.removeAllViews()
        places.forEachIndexed { i, place ->
            val phrase = "take me to ${place.name.lowercase()}"
            val chip = TextView(this).apply {
                text = "Take me to ${place.name}"
                textSize = 14f
                setTextColor(0xFFE6E8EC.toInt())
                gravity = Gravity.CENTER
                setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
                val shape = GradientDrawable().apply {
                    cornerRadius = 22 * d
                    setStroke((1 * d).toInt(), 0x33FFFFFF)
                }
                val mask = GradientDrawable().apply { cornerRadius = 22 * d; setColor(Color.WHITE) }
                background = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), shape, mask)
                setOnClickListener { runTyped(phrase) }
            }
            chips.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins((4 * d).toInt(), 0, (4 * d).toInt(), 0) })
            if (!chipsAnimated) { // one gentle entrance, staggered, the first time only
                chip.alpha = 0f
                chip.translationY = 8 * d
                chip.animate().alpha(1f).translationY(0f).setStartDelay(400L + i * 120L).setDuration(400).start()
            }
        }
        if (places.isNotEmpty()) chipsAnimated = true
    }

    private fun showLastTrip() {
        val trip = Journeys.all(this).firstOrNull()
        if (trip == null) {
            lastTrip.visibility = View.GONE
        } else {
            lastTrip.text = "Last trip: ${trip.name}, ${Journeys.whenText(this, trip.atMillis)}"
            lastTrip.visibility = View.VISIBLE
        }
    }

    private fun runTyped(text: String) {
        val state = DriveBus.current.state
        Log.i(TAG, "suggestion tapped: \"$text\" (state=$state)")
        if (state != DriveState.IDLE) return // the chips are faded out unless idle; guard anyway
        startService(
            Intent(this, DriveService::class.java)
                .setAction(DriveService.ACTION_COMMAND)
                .putExtra(DriveService.EXTRA_TEXT, text)
        )
    }

    override fun onPause() {
        DriveService.statusListener = null
        DriveBus.listener = null
        super.onPause()
    }

    private var requestedRuntime = false
    private val prefs by lazy { getSharedPreferences("setup", MODE_PRIVATE) }

    override fun onResume() {
        super.onResume()
        refreshLog()
        showSuggestions()
        showLastTrip()
        render(DriveBus.current)
        DriveBus.listener = { render(it) }
        DriveService.statusListener = { refreshLog() }
        ensureSetup()
    }

    /** Runs on every resume. Each call does at most one thing, then waits for the user to come back. */
    private fun ensureSetup() {
        // 1. Normal permissions: one system dialog, asked once per launch.
        val missing = runtimePermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty() && !requestedRuntime) {
            requestedRuntime = true
            Log.i(TAG, "setup: requesting ${missing.joinToString { it.substringAfterLast('.') }}")
            requestPermissions(missing.toTypedArray(), permissionRequest)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "setup: microphone permission missing, cannot start")
            status.text = "Microphone permission is required"
            return
        }
        // 2. Start the drive session by itself, once per app process.
        if (!autoStarted) {
            autoStarted = true
            Log.i(TAG, "setup: auto-starting drive session")
            startSession()
        }
        // 3. Special settings screens. Each one opens automatically a single time.
        val overlay = Settings.canDrawOverlays(this)
        val listener = hasListenerAccess()
        val spotify = SpotifyAuth(this)
        val connected = spotify.isConnected()
        Log.d(TAG, "setup check: overlay=$overlay musicAccess=$listener spotify=$connected")
        if (!overlay && !askedBefore("overlay")) {
            Log.i(TAG, "setup: opening overlay settings (first time)")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (!listener && !askedBefore("listener")) {
            Log.i(TAG, "setup: opening music access settings (first time)")
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return
        }
        if (!connected && !askedBefore("spotify")) {
            Log.i(TAG, "setup: starting Spotify login (first time)")
            spotify.connect { ok ->
                Log.i(TAG, "setup: Spotify login finished, success=$ok")
                val message = if (ok) "Spotify connected" else "Spotify connection failed"
                DriveService.lastStatus = message
                runOnUiThread { status.text = message }
            }
        }
    }

    /** True if we already opened this screen once; otherwise records it now and returns false. */
    private fun askedBefore(key: String): Boolean {
        if (prefs.getBoolean(key, false)) return true
        prefs.edit().putBoolean(key, true).apply()
        return false
    }

    private fun hasListenerAccess(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true

    private fun runtimePermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startSession() {
        startForegroundService(Intent(this, DriveService::class.java).setAction(DriveService.ACTION_START))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissions.forEachIndexed { i, p ->
            val granted = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "permission ${p.substringAfterLast('.')}: ${if (granted) "granted" else "denied"}")
        }
        // onResume runs right after this and continues the setup.
    }

    private fun send(action: String) {
        // Talk/Stop only make sense once the session is running; the service ignores them otherwise.
        startService(Intent(this, DriveService::class.java).setAction(action))
    }
}
