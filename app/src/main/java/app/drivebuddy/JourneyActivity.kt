package app.drivebuddy

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Your last trip, a one-tap way to go again, and the few trips before it. */
class JourneyActivity : Activity() {

    private val tag = "DriveBuddy"
    private val bg = 0xFF0A0C0F.toInt()
    private val grey = 0xFF8A919E.toInt()
    private val violet = 0xFF5B3FBF.toInt()
    private val d by lazy { resources.displayMetrics.density }
    private lateinit var content: LinearLayout
    private var animated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Back"
            textSize = 14f
            setTextColor(grey)
            setPadding(0, 0, 0, pad / 2)
            setOnClickListener { finish() }
        })
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(pad, insets.systemWindowInsetTop + pad, pad, insets.systemWindowInsetBottom + pad)
            insets
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        content.removeAllViews()
        val trips = Journeys.all(this)
        Log.d(tag, "journey screen: ${trips.size} trips")
        if (trips.isEmpty()) {
            content.addView(text("No trips yet", 32f, Color.WHITE, "sans-serif-light"))
            content.addView(text("Say \"Hi Oreo, take me to office\" and the trip shows up here.", 16f, grey).apply {
                setPadding(0, (12 * d).toInt(), 0, 0)
            })
            return
        }

        val last = trips.first()
        val hero = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hero.addView(text(Journeys.whenText(this, last.atMillis), 14f, grey))
        hero.addView(text(last.name, 40f, Color.WHITE, "sans-serif-light").apply { setPadding(0, (4 * d).toInt(), 0, 0) })
        if (!last.query.equals(last.name, ignoreCase = true)) {
            hero.addView(text(last.query, 16f, grey).apply { setPadding(0, (4 * d).toInt(), 0, 0) })
        }
        hero.addView(goButton("Navigate again") { go(last) })
        content.addView(hero)
        if (!animated) { // one gentle entrance the first time the screen opens
            animated = true
            hero.alpha = 0f
            hero.translationY = 12 * d
            hero.animate().alpha(1f).translationY(0f).setDuration(500).start()
        }

        val earlier = trips.drop(1)
        if (earlier.isNotEmpty()) {
            content.addView(text("Earlier", 14f, grey).apply { setPadding(0, (32 * d).toInt(), 0, (4 * d).toInt()) })
            earlier.forEach { content.addView(row(it)) }
        }
    }

    private fun row(trip: Trip): View {
        val pad = (12 * d).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad, 0, pad)
            background = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, ColorDrawable(Color.WHITE))
            setOnClickListener { go(trip) }
            addView(text(trip.name, 20f, Color.WHITE, "sans-serif-medium"))
            addView(text(Journeys.whenText(context, trip.atMillis), 14f, grey))
        }
    }

    /** The one big action on the screen: a filled violet pill with a touch ripple. */
    private fun goButton(label: String, onTap: () -> Unit): View {
        val fill = GradientDrawable().apply { cornerRadius = 32 * d; setColor(violet) }
        val mask = GradientDrawable().apply { cornerRadius = 32 * d; setColor(Color.WHITE) }
        return text(label, 20f, Color.WHITE, "sans-serif-medium").apply {
            gravity = android.view.Gravity.CENTER
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), fill, mask)
            setOnClickListener { onTap() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (64 * d).toInt()
            ).apply { topMargin = (28 * d).toInt() }
        }
    }

    private fun go(trip: Trip) {
        Log.i(tag, "journey: navigate again to \"${trip.name}\"")
        if (PhoneActions(this).navigate(trip.query)) {
            Journeys.record(this, trip.name, trip.query) // moves it to the top
            refresh()
        } else {
            Log.w(tag, "journey: could not open Maps for \"${trip.name}\"")
            Toast.makeText(this, "Couldn't open Google Maps", Toast.LENGTH_SHORT).show()
        }
    }

    private fun text(value: String, size: Float, color: Int, family: String? = null) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (family != null) typeface = Typeface.create(family, Typeface.NORMAL)
    }
}