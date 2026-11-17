package app.drivebuddy.ui

import android.R
import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.util.Log
import app.drivebuddy.features.navigation.Place
import app.drivebuddy.features.navigation.SavedPlaces
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import app.drivebuddy.common.LocationHelper
import app.drivebuddy.features.navigation.PlaceDetail
import app.drivebuddy.features.navigation.PlaceSuggestClient
import app.drivebuddy.features.navigation.PlaceSuggestion
import app.drivebuddy.features.navigation.SuggestQuota
import java.util.UUID
import kotlin.concurrent.thread

/** Add, edit and delete the places you can navigate to by name. Same dark look as the home screen. */
class PlacesActivity : Activity() {

    private val bg = 0xFF0A0C0F.toInt()
    private val grey = 0xFF8A919E.toInt()
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private val d by lazy { resources.displayMetrics.density }
    private val tag = "DriveBuddy"

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
        root.addView(TextView(this).apply {
            text = "Places"
            textSize = 32f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        root.addView(TextView(this).apply {
            text = "Say \"take me to\" and a name from this list."
            textSize = 14f
            setTextColor(grey)
            setPadding(0, pad / 4, 0, pad / 2)
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        empty = TextView(this).apply {
            text = "No places yet. Add one, like Home, and say \"take me to home\"."
            textSize = 16f
            setTextColor(grey)
            setPadding(0, pad / 2, 0, 0)
        }
        val scroll = ScrollView(this).apply {
            addView(LinearLayout(this@PlacesActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(list)
                addView(empty)
            })
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(TextView(this).apply {
            text = "Add a place"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, pad / 2, 0, pad / 2)
            background = ripple()
            setOnClickListener { showEditor(null) }
        })

        // Edge-to-edge on Android 15: stay clear of the system bars.
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(pad, insets.systemWindowInsetTop + pad, pad, insets.systemWindowInsetBottom + pad)
            insets
        }
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val places = SavedPlaces.all(this)
        Log.d(tag, "places screen: showing ${places.size} places")
        empty.visibility = if (places.isEmpty()) View.VISIBLE else View.GONE
        places.forEach { list.addView(row(it)) }
    }

    private fun row(place: Place): View {
        val pad = (14 * d).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad, 0, pad)
            background = ripple()
            setOnClickListener { showEditor(place) }
            addView(TextView(context).apply {
                text = place.name
                textSize = 20f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(TextView(context).apply {
                text = place.address
                textSize = 14f
                setTextColor(grey)
            })
            if (place.lat != null) addView(TextView(context).apply {
                text = "Exact location"
                textSize = 13f
                setTextColor(0xFF4CAF7D.toInt())
            })
        }
    }

    /** A soft touch highlight, so rows and links respond under your finger. */
    private fun ripple() = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, ColorDrawable(Color.WHITE))

    /** One dialog for both adding (existing = null) and editing. */
    /** One dialog for both adding (existing = null) and editing. The address field suggests places from Google Maps. */
    private fun showEditor(existing: Place?) {
        Log.d(tag, "places screen: ${if (existing == null) "add" else "edit \"${existing.name}\""} dialog opened")
        val pad = (24 * d).toInt()
        val main = Handler(Looper.getMainLooper())
        val quota = SuggestQuota(this)
        var token = UUID.randomUUID().toString() // one typing-and-picking session
        var picked: PlaceDetail? = null
        var searchId = 0                         // answers to older keystrokes are ignored
        var programmatic = false                 // true while we fill the field ourselves
        var pending: Runnable? = null
        var biasLat: Double? = null
        var biasLng: Double? = null

        val name = EditText(this).apply {
            hint = "Name, like Home"
            setText(existing?.name ?: "")
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(grey)
        }
        val address = EditText(this).apply {
            hint = "Start typing a place or address"
            setText(existing?.address ?: "")
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(grey)
        }
        val status = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF4CAF7D.toInt())
            setPadding(0, pad / 4, 0, pad / 4)
            if (existing?.lat != null) text = "Exact location saved" else visibility = View.GONE
        }
        val suggestionsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun pick(s: PlaceSuggestion) {
            Log.i(tag, "places screen: suggestion picked")
            programmatic = true
            address.setText(s.text)
            address.setSelection(address.text.length)
            programmatic = false
            suggestionsBox.removeAllViews()
            status.text = "Getting the exact location..."
            status.visibility = View.VISIBLE
            val myId = ++searchId
            val sessionToken = token
            token = UUID.randomUUID().toString() // a new session for the next search
            quota.record()
            thread(name = "place-details") {
                val detail = PlaceSuggestClient.details(s.placeId, sessionToken)
                runOnUiThread {
                    if (myId != searchId) return@runOnUiThread
                    picked = detail
                    status.text = if (detail != null) "Exact location saved" else "Couldn't get the exact location"
                }
            }
        }

        fun showSuggestions(list: List<PlaceSuggestion>) {
            suggestionsBox.removeAllViews()
            list.forEach { s ->
                suggestionsBox.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, pad / 3, 0, pad / 3)
                    background = ripple()
                    setOnClickListener { pick(s) }
                    addView(TextView(context).apply { text = s.title; textSize = 16f; setTextColor(Color.WHITE) })
                    if (s.subtitle.isNotBlank()) addView(TextView(context).apply {
                        text = s.subtitle; textSize = 13f; setTextColor(grey)
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    })
                })
            }
        }

        address.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (programmatic) return
                picked = null                       // the text changed, so any earlier exact point no longer applies
                status.visibility = View.GONE
                pending?.let { main.removeCallbacks(it) }
                val typed = s?.toString()?.trim().orEmpty()
                val myId = ++searchId
                if (typed.length < 3) {
                    suggestionsBox.removeAllViews()
                    return
                }
                val run = Runnable {
                    if (!quota.canUse()) {
                        Log.i(tag, "suggest: daily allowance used up")
                        return@Runnable
                    }
                    quota.record()
                    thread(name = "place-suggest") {
                        val found = PlaceSuggestClient.suggest(typed, token, biasLat, biasLng)
                        runOnUiThread { if (myId == searchId && found != null) showSuggestions(found) }
                    }
                }
                pending = run
                main.postDelayed(run, 400) // wait for a pause in typing
            }
        })

        // Lean the suggestions towards where you are, when that is cheap to know.
        if (LocationHelper.hasPermission(this) && LocationHelper.isLocationOn(this)) {
            LocationHelper.current(this) { location ->
                biasLat = location?.latitude
                biasLng = location?.longitude
            }
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(name)
            addView(address)
            addView(status)
            addView(suggestionsBox)
        }

        val builder = AlertDialog.Builder(this, R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (existing == null) "Add a place" else "Edit place")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save", null) // wired below so a bad entry keeps the dialog open
            .setNegativeButton("Cancel", null)
        if (existing != null) {
            builder.setNeutralButton("Delete") { _, _ ->
                SavedPlaces.delete(this, existing.name)
                refresh()
            }
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when {
                    name.text.isBlank() -> name.error = "Enter a name"
                    address.text.isBlank() -> address.error = "Enter an address"
                    else -> {
                        // Keep the old exact point when the address wasn't touched; a newly picked one wins.
                        val keep = existing?.takeIf { it.lat != null && it.address == address.text.toString().trim() }
                        SavedPlaces.save(
                            this, name.text.toString(), address.text.toString(), existing?.name,
                            picked?.lat ?: keep?.lat, picked?.lng ?: keep?.lng,
                        )
                        refresh()
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.setOnDismissListener {
            pending?.let { main.removeCallbacks(it) }
            searchId++ // late answers go nowhere
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }
}