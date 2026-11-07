package app.drivebuddy.ui

import android.Manifest
import android.R
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.net.Uri
import android.provider.Settings
import app.drivebuddy.features.music.spotify.SpotifyAuth
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import java.util.Calendar
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.Log
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.CompositePageTransformer
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2
import android.view.ViewGroup
import app.drivebuddy.features.navigation.Journeys
import app.drivebuddy.common.LatencyLog
import app.drivebuddy.features.navigation.SavedPlaces
import app.drivebuddy.service.DriveService
import kotlin.math.abs

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
    private lateinit var choicesBox: LinearLayout

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
                overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
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

        choicesBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            isClickable = true // swallow taps so the orb underneath is not triggered
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xB30A0C0F.toInt(), 0xF20A0C0F.toInt()),
            )
            setPadding(pad, pad, pad, pad)
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(pad, insets.systemWindowInsetTop + pad, pad, insets.systemWindowInsetBottom + pad)
                insets
            }
        }

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

        setContentView(FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(choicesBox, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        })

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

    /** Draws the "pick one" card (nearby places), or hides it when there is nothing to pick. */
    /** Draws the full-screen "pick one" layer (nearby places) over the orb, or fades it away when there is none. */
    private fun showChoices(card: ChoiceCard?) {
        choicesBox.removeAllViews()
        DriveBus.pageTo = null
        if (card == null || card.rows.isEmpty()) {
            choicesBox.visibility = View.GONE
            return
        }
        Log.i(TAG, "choices: showing ${card.rows.size} row(s) for \"${card.title}\"")
        if (card.paged) { showPaged(card); return }
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun pill(fill: Int, stroke: Int?) = GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            setColor(fill)
            if (stroke != null) setStroke(dp(1), stroke)
        }

        choicesBox.visibility = View.VISIBLE
        // Heavy scrim: the orb behind is only a faint glow.
        choicesBox.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFA0B0A12.toInt(), 0xF70B0A12.toInt(), 0xF20B0A12.toInt())
        )
        choicesBox.isClickable = true // swallow taps so nothing behind reacts

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(72), dp(20), dp(28))
        }
        column.addView(TextView(this).apply {
            text = card.title.uppercase()
            setTextColor(0xFFF2A93B.toInt()); textSize = 12f; letterSpacing = 0.18f
        })
        column.addView(TextView(this).apply {
            text = "Pick one"
            setTextColor(0xFFF4F1FA.toInt()); textSize = 30f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setPadding(0, dp(4), 0, dp(20))
        })

        card.rows.forEachIndexed { i, row ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(18), dp(14), dp(18))
                background = GradientDrawable().apply {
                    cornerRadius = dp(24).toFloat()
                    setColor(0xE617132A.toInt())
                    setStroke(dp(1), 0xFF372E55.toInt())
                }
                elevation = dp(6).toFloat()
                alpha = 0f; translationY = dp(28).toFloat()
                setOnClickListener { DriveBus.tapChoice("${i + 1}") }
            }
            item.addView(TextView(this).apply {
                text = "${i + 1}"; gravity = Gravity.CENTER
                setTextColor(0xFFF2A93B.toInt()); textSize = 14f
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x1AF2A93B); setStroke(dp(1), 0xFFF2A93B.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(14) }
            })
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { rightMargin = dp(12) }
            }
            texts.addView(TextView(this).apply {
                text = row.label; setTextColor(0xFFF4F1FA.toInt()); textSize = 16f
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
            texts.addView(TextView(this).apply {
                text = row.detail; setTextColor(0xFF9C95B5.toInt()); textSize = 13f
                setPadding(0, dp(4), 0, 0)
            })
            item.addView(texts)
            item.addView(TextView(this).apply {
                text = "→"; gravity = Gravity.CENTER
                setTextColor(Color.WHITE); textSize = 18f
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF7B5CF0.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            })
            column.addView(item, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) })
            item.animate().alpha(1f).translationY(0f)
                .setStartDelay(90L * i).setDuration(320).start()
        }

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        fun footerButton(label: String, key: String, filled: Boolean) = TextView(this).apply {
            text = label; gravity = Gravity.CENTER; textSize = 15f
            setTextColor(if (filled) 0xFFF4F1FA.toInt() else 0xFF9C95B5.toInt())
            background = pill(if (filled) 0x337B5CF0 else Color.TRANSPARENT, 0xFF372E55.toInt())
            setPadding(dp(28), dp(14), dp(28), dp(14))
            setOnClickListener { DriveBus.tapChoice(key) }
        }
        if (card.hasMore) footer.addView(footerButton("More", "more", true),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { rightMargin = dp(12) })
        footer.addView(footerButton("Cancel", "cancel", false))
        column.addView(footer)
        column.addView(TextView(this).apply {
            text = card.hint; setTextColor(0xFF6E678A.toInt()); textSize = 13f
            gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0)
        })

        choicesBox.addView(ScrollView(this).apply { addView(column) })
    }

    /** One full-screen page per story or place; swipe sideways, tap the big button to pick. */
    private fun showPaged(card: ChoiceCard) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun pill(fill: Int, stroke: Int?) = GradientDrawable().apply {
            cornerRadius = dp(26).toFloat(); setColor(fill)
            if (stroke != null) setStroke(dp(1), stroke)
        }
        choicesBox.visibility = View.VISIBLE
        choicesBox.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xF70B0A12.toInt(), 0xF40B0A12.toInt(), 0xEB0B0A12.toInt())
        )
        choicesBox.isClickable = true

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(64), 0, dp(24))
        }
        val header = TextView(this).apply {
            setTextColor(0xFFF2A93B.toInt()); textSize = 12f; letterSpacing = 0.18f
            setPadding(dp(28), 0, dp(28), dp(14))
        }
        val dots = TextView(this).apply {
            gravity = Gravity.CENTER; textSize = 14f; letterSpacing = 0.3f
            setTextColor(0xFFF2A93B.toInt()); setPadding(0, dp(14), 0, dp(10))
        }
        fun select(pos: Int) {
            header.text = "${card.title.uppercase()}  ·  ${pos + 1} OF ${card.rows.size}"
            dots.text = card.rows.indices.joinToString(" ") { if (it == pos) "●" else "○" }
        }

        fun pageFor(i: Int): View {
            val row = card.rows[i]
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(22), dp(24), dp(22))
                background = GradientDrawable().apply {
                    cornerRadius = dp(28).toFloat()
                    setColor(0xF217132A.toInt()); setStroke(dp(1), 0xFF372E55.toInt())
                }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(context).apply {
                        text = "${i + 1}"; gravity = Gravity.CENTER
                        setTextColor(0xFFF2A93B.toInt()); textSize = 15f
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(0x1AF2A93B); setStroke(dp(2), 0xFFF2A93B.toInt())
                        }
                        layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(12) }
                    })
                    if (!card.compact) addView(TextView(context).apply {
                        text = row.detail.uppercase(); textSize = 12f; letterSpacing = 0.14f
                        setTextColor(0xFF9C95B5.toInt())
                    })
                })
                if (card.compact) addView(TextView(context).apply {
                    text = row.detail; textSize = 48f; setTextColor(0xFFF4F1FA.toInt())
                    typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                    setPadding(0, dp(20), 0, 0)
                })
                addView(TextView(context).apply {
                    text = row.label
                    textSize = when {
                        card.compact -> 24f
                        row.label.length > 90 -> 19f
                        else -> 22f
                    }
                    setTextColor(0xFFF4F1FA.toInt())
                    typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                    if (card.compact) { maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
                    setPadding(0, dp(if (card.compact) 6 else 16), 0, 0)
                })
                if (card.compact) {
                    if (row.body.isNotBlank()) addView(TextView(context).apply {
                        text = row.body; textSize = 15f; setTextColor(0xFF9C95B5.toInt())
                        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(12), 0, 0)
                    })
                    addView(View(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)))
                } else {
                    // Takes all the space left under the title, then shows only as many lines as fit.
                    addView(TextView(context).apply {
                        text = row.body; textSize = 15f; setTextColor(0xFF9C95B5.toInt())
                        setLineSpacing(0f, 1.15f)
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(12), 0, dp(8))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                        addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
                            val tv = v as TextView
                            val room = bottom - top - tv.paddingTop - tv.paddingBottom
                            val fit = room / tv.lineHeight
                            val lines = fit.coerceIn(1, 4)
                            tv.alpha = if (fit < 1) 0f else 1f
                            if (tv.maxLines != lines) tv.maxLines = lines
                        }
                    })
                }
                addView(View(context), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, if (card.compact) dp(28) else 0, if (card.compact) 0f else 1f))
                addView(TextView(context).apply {
                    text = "${card.action}  →"; gravity = Gravity.CENTER
                    setTextColor(Color.WHITE); textSize = 17f
                    setPadding(dp(16), dp(15), dp(16), dp(15))
                    background = pill(0xFF7B5CF0.toInt(), null)
                    setOnClickListener { DriveBus.tapChoice("${i + 1}") }
                })
            }
        }

        var follow = true
        val pager = ViewPager2(this).apply {
            setPadding(dp(20), 0, dp(20), 0)
            offscreenPageLimit = 1
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = card.rows.size
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                    object : RecyclerView.ViewHolder(FrameLayout(parent.context).apply {
                        layoutParams = RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }) {}
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    (holder.itemView as FrameLayout).apply {
                        removeAllViews()
                        addView(pageFor(position), FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            if (card.compact) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER_VERTICAL,
                        ))
                    }
                }
            }
            setPageTransformer(CompositePageTransformer().apply {
                addTransformer(MarginPageTransformer(dp(12)))
                addTransformer(ViewPager2.PageTransformer { page, pos ->
                    val a = abs(pos)
                    page.alpha = 1f - 0.45f * a
                    page.scaleY = 1f - 0.06f * a
                })
            })
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    select(position)
                    Log.d(TAG, "choices: now on page ${position + 1}")
                }

                override fun onPageScrollStateChanged(state: Int) {
                    if (state == ViewPager2.SCROLL_STATE_DRAGGING && follow) {
                        follow = false
                        Log.d(TAG, "choices: you took over, auto-slide off")
                    }
                }
            })
        }
        (pager.getChildAt(0) as RecyclerView).clipToPadding = false
        DriveBus.pageTo = { pos ->
            if (follow && pos in card.rows.indices) pager.setCurrentItem(pos, true)
        }
        select(0)

        root.addView(header)
        root.addView(pager, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(dots)

        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun footerButton(label: String, key: String, filled: Boolean) = TextView(this).apply {
            text = label; gravity = Gravity.CENTER; textSize = 15f
            setTextColor(if (filled) 0xFFF4F1FA.toInt() else 0xFF9C95B5.toInt())
            background = pill(if (filled) 0x337B5CF0 else Color.TRANSPARENT, 0xFF372E55.toInt())
            setPadding(dp(28), dp(14), dp(28), dp(14))
            setOnClickListener { DriveBus.tapChoice(key) }
        }
        if (card.hasMore) footer.addView(footerButton("More", "more", true),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { rightMargin = dp(12) })
        footer.addView(footerButton("Cancel", "cancel", false))
        root.addView(footer)
        root.addView(TextView(this).apply {
            text = card.hint; setTextColor(0xFF6E678A.toInt()); textSize = 13f
            gravity = Gravity.CENTER; setPadding(dp(20), dp(14), dp(20), 0)
        })

        choicesBox.addView(root)
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
        DriveBus.choicesListener = null
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
        DriveBus.choicesListener = { showChoices(it) }
        showChoices(DriveBus.choices)
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
