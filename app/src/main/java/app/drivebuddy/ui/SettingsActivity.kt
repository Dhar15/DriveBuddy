package app.drivebuddy.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

private const val TAG = "DriveBuddy"

/** Home for everything that isn't driving: saved places and permissions. */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0A0C0F.toInt())
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Settings"
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setPadding(0, 0, 0, pad / 2)
        })
        root.addView(row("Places", "Home, work and other saved places", PlacesActivity::class.java))
        root.addView(row("Permissions", "Microphone, music, Do Not Disturb and more", SetupActivity::class.java))

        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(pad, insets.systemWindowInsetTop + pad, pad, insets.systemWindowInsetBottom + pad)
            insets
        }
        setContentView(root)
        Log.i(TAG, "settings screen opened")
    }

    private fun row(title: String, hint: String, target: Class<*>): LinearLayout {
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (16 * d).toInt(), 0, (16 * d).toInt())
            val text = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
            text.addView(TextView(this@SettingsActivity).apply { this.text = title; textSize = 17f; setTextColor(Color.WHITE) })
            text.addView(TextView(this@SettingsActivity).apply { this.text = hint; textSize = 13f; setTextColor(0xFF8A919E.toInt()) })
            addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@SettingsActivity).apply { this.text = "›"; textSize = 22f; setTextColor(0xFF6B7280.toInt()) })
            setOnClickListener {
                Log.i(TAG, "settings: opened $title")
                startActivity(Intent(this@SettingsActivity, target))
            }
        }
    }
}