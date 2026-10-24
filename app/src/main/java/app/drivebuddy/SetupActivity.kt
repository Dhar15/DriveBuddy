package app.drivebuddy

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.drivebuddy.spotify.SpotifyAuth
import android.widget.Switch
import android.content.res.ColorStateList

private const val TAG = "DriveBuddy"

/** One place to see every permission DriveBuddy uses, and fix any that are off. */
class SetupActivity : Activity() {

    private class Item(
        val title: String,
        val hint: String,
        val granted: () -> Boolean,
        val fix: () -> Unit,
        val runtime: Boolean = false,
    )
    private lateinit var list: LinearLayout
    private lateinit var items: List<Item>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()

        items = buildList {
            add(runtime("Microphone", "Hear you and the wake word", Manifest.permission.RECORD_AUDIO))
            add(runtime("Contacts", "Call people by name", Manifest.permission.READ_CONTACTS))
            add(runtime("Phone calls", "Place calls for you", Manifest.permission.CALL_PHONE))
            add(Item("Calendar", "Set reminders in your calendar",
                { PhoneControls.hasCalendarAccess(this@SetupActivity) },
                { requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 1) },
                runtime = true))
            if (Build.VERSION.SDK_INT >= 33) {
                add(runtime("Notifications", "Show the drive session notification", Manifest.permission.POST_NOTIFICATIONS))
            }
            add(Item("Display over other apps", "Open Maps and other apps while driving",
                { Settings.canDrawOverlays(this@SetupActivity) },
                { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }))
            add(Item("Music control", "Pause, skip and stop your music app",
                { hasListenerAccess() },
                { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }))
            add(Item("Do Not Disturb", "Turn it on and off by voice",
                { PhoneControls.hasDndAccess(this@SetupActivity) },
                { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }))
            add(Item("Spotify", "Play songs from your account",
                { SpotifyAuth(this@SetupActivity).isConnected() },
                {
                    SpotifyAuth(this@SetupActivity).connect { ok ->
                        Log.i(TAG, "permissions: Spotify login finished, success=$ok")
                        runOnUiThread { rebuild() }
                    }
                }))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0A0C0F.toInt())
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Permissions"
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setPadding(0, 0, 0, pad / 2)
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(pad, insets.systemWindowInsetTop + pad, pad, insets.systemWindowInsetBottom + pad)
            insets
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    /** Redraws every row from the live permission state. Runs each time the screen comes back from Settings. */
    private fun rebuild() {
        val d = resources.displayMetrics.density
        list.removeAllViews()
        items.forEach { item ->
            val on = item.granted()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (14 * d).toInt(), 0, (14 * d).toInt())
                setOnClickListener {
                    Log.i(TAG, "permissions: tapped \"${item.title}\" (on=$on)")
                    if (on && item.runtime) {
                        // Already allowed: the only place to turn it off is the app's own settings page.
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    } else {
                        item.fix()
                    }
                }
            }
            val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            text.addView(TextView(this).apply { this.text = item.title; textSize = 16f; setTextColor(Color.WHITE) })
            text.addView(TextView(this).apply { this.text = item.hint; textSize = 13f; setTextColor(0xFF8A919E.toInt()) })
            row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            row.addView(Switch(this).apply {
                isChecked = on
                isClickable = false   // the row handles the tap; the system screen does the real change
                isFocusable = false
                thumbTintList = ColorStateList(states, intArrayOf(0xFFE6E0FF.toInt(), 0xFF8A919E.toInt()))
                trackTintList = ColorStateList(states, intArrayOf(0xFF7457DB.toInt(), 0xFF2A2F3A.toInt()))
            })
            list.addView(row)
        }
        Log.i(TAG, "permissions: " + items.joinToString { "${it.title}=${if (it.granted()) "on" else "off"}" })
    }

    private fun runtime(title: String, hint: String, permission: String) = Item(
        title, hint,
        granted = { checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED },
        fix = { requestPermissions(arrayOf(permission), 1) },
        runtime = true,
    )

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissions.forEachIndexed { i, p ->
            val granted = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "permissions: ${p.substringAfterLast('.')} ${if (granted) "granted" else "denied"}")
            // Denied twice: Android stops showing the dialog, so send the user to the app's settings page instead.
            if (!granted && !shouldShowRequestPermissionRationale(p)) {
                Log.i(TAG, "permissions: dialog blocked, opening app settings")
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        rebuild()
    }

    private fun hasListenerAccess(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
}