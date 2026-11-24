package app.drivebuddy.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.drivebuddy.common.FileLog
import app.drivebuddy.common.LogFilter
import app.drivebuddy.common.LogLine
import app.drivebuddy.common.LogParser
import app.drivebuddy.common.LogQuery
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import java.util.Calendar

/** Shows the saved log with filters and sorting, and shares what is on screen. */
class LogActivity : Activity() {
    private val tag = "DriveBuddy"
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val adapter = LogAdapter()
    private var files: List<File> = emptyList() // newest first
    private var shown: List<LogLine> = emptyList()
    private var query = LogQuery(newestFirst = true)
    private var loadId = 0
    private var pending: Runnable? = null

    private lateinit var days: Spinner
    private lateinit var count: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        files = FileLog.files()
        val ink = Color.parseColor("#E6E6EA")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E0E12"))
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(16.dp(), b.top + 12.dp(), 16.dp(), b.bottom + 8.dp())
                insets
            }
        }

        // Header: title, Copy, Share
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@LogActivity).apply {
                text = "Logs"; textSize = 26f; setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(pill("Copy", filled = false) { copy() })
            addView(pill("Share", filled = true) { share() }, lp(WRAP_CONTENT, WRAP_CONTENT, left = 8.dp()))
        })

        // Day and level pickers, side by side, each in a rounded card
        days = Spinner(this).apply {
            val labels = listOf("All days") + files.map { dayLabel(it.name) }
            adapter = ArrayAdapter(this@LogActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            if (files.isNotEmpty()) setSelection(1)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = reload()
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        val levels = listOf("All levels" to 'V', "Info and up" to 'I', "Warnings and up" to 'W', "Errors only" to 'E')
        val levelSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@LogActivity, android.R.layout.simple_spinner_dropdown_item, levels.map { it.first })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    query = query.copy(minLevel = levels[pos].second); reload()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(card(days), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(card(levelSpinner), lp(0, WRAP_CONTENT, weight = 1f, left = 8.dp()))
        }, lp(MATCH_PARENT, WRAP_CONTENT, top = 14.dp()))

        // Toggles (checked state is set before the listener so nothing reloads twice)
        val newest = CheckBox(this).apply {
            text = "Newest first"; setTextColor(ink); isChecked = true
            setOnCheckedChangeListener { _, on -> query = query.copy(newestFirst = on); reload() }
        }
        val appOnly = CheckBox(this).apply {
            text = "DriveBuddy only"; setTextColor(ink); isChecked = true
            setOnCheckedChangeListener { _, on -> query = query.copy(appOnly = on); reload() }
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(newest, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(appOnly, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }, lp(MATCH_PARENT, WRAP_CONTENT, top = 4.dp()))

        // Search
        val search = EditText(this).apply {
            hint = "Search (commute, TTS, mic…)"
            setTextColor(ink); setHintTextColor(Color.parseColor("#6E6E78"))
            isSingleLine = true; background = null; textSize = 15f
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    pending?.let { ui.removeCallbacks(it) }
                    pending = Runnable { query = query.copy(text = s?.toString().orEmpty()); reload() }
                    ui.postDelayed(pending!!, 300)
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        root.addView(card(search), lp(MATCH_PARENT, WRAP_CONTENT, top = 4.dp()))

        count = TextView(this).apply { setTextColor(Color.parseColor("#8A8A93")); textSize = 12f }
        root.addView(count, lp(WRAP_CONTENT, WRAP_CONTENT, top = 10.dp()))

        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LogActivity)
            adapter = this@LogActivity.adapter
        }
        root.addView(list, lp(MATCH_PARENT, 0, weight = 1f, top = 6.dp()))
        setContentView(root)
        Log.i(tag, "log viewer opened: ${files.size} day files")
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f, left: Int = 0, top: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(left, top, 0, 0) }

    /** Rounded dark container, 48dp tall, so pickers and the search box look like one family. */
    private fun card(v: View) = LinearLayout(this).apply {
        setPadding(12.dp(), 0, 8.dp(), 0)
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { setColor(Color.parseColor("#181820")); cornerRadius = 14.dp().toFloat() }
        addView(v, LinearLayout.LayoutParams(MATCH_PARENT, 48.dp()))
    }

    private fun pill(label: String, filled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(Color.parseColor(if (filled) "#0E0E12" else "#B9B2F7"))
        setPadding(20.dp(), 9.dp(), 20.dp(), 9.dp())
        background = GradientDrawable().apply {
            cornerRadius = 40.dp().toFloat()
            if (filled) setColor(Color.parseColor("#B9B2F7")) else setStroke(1.dp(), Color.parseColor("#8B7CF6"))
        }
        setOnClickListener { onClick() }
    }

    /** "drivebuddy-2026-11-24.log" -> "24th-Nov" */
    private fun dayLabel(fileName: String): String {
        val raw = fileName.removePrefix("drivebuddy-").removeSuffix(".log")
        val date = try { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(raw) } catch (e: Exception) { null } ?: return raw
        val n = Calendar.getInstance().apply { time = date }.get(Calendar.DAY_OF_MONTH)
        val suffix = if (n in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$suffix-" + SimpleDateFormat("MMM", Locale.US).format(date)
    }

    private fun copy() {
        if (shown.isEmpty()) { Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show(); return }
        val text = shown.joinToString("\n") { it.raw }.take(300_000) // keep well under clipboard limits
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("DriveBuddy log", text))
        Log.i(tag, "log copied: ${shown.size} rows, ${text.length} chars")
        Toast.makeText(this, "Copied ${shown.size} lines", Toast.LENGTH_SHORT).show()
    }

    private fun reload() {
        if (!::days.isInitialized || files.isEmpty()) { if (::count.isInitialized) count.text = "No log files yet"; return }
        val id = ++loadId
        val sel = days.selectedItemPosition
        val q = query
        io.execute {
            val chosen = if (sel <= 0) files.reversed() else listOf(files[sel - 1])
            val lines = chosen.flatMap { f ->
                try { f.readLines() } catch (e: Exception) { Log.w(tag, "log read failed: ${e.javaClass.simpleName}"); emptyList() }
            }.mapNotNull(LogParser::parse)
            val result = LogFilter.apply(lines, q)
            ui.post {
                if (id != loadId) return@post
                shown = result.rows
                adapter.rows = result.rows
                adapter.showDate = sel <= 0
                adapter.notifyDataSetChanged()
                count.text = "${result.total} lines" +
                        if (result.total > result.rows.count { !it.isBreak }) " (showing the newest ${result.rows.count { !it.isBreak }})" else ""
            }
        }
    }

    private fun share() {
        val dir = FileLog.dir()
        if (dir == null || shown.isEmpty()) { Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show(); return }
        io.execute {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val out = File(dir, "export-$stamp.txt")
                out.writeText(shown.joinToString("\n") { it.raw })
                val uri = FileProvider.getUriForFile(this, "$packageName.logs", out)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra(Intent.EXTRA_SUBJECT, "DriveBuddy log")
                    clipData = android.content.ClipData.newRawUri("log", uri)
                }
                Log.i(tag, "log export: ${shown.size} rows")
                ui.post { startActivity(Intent.createChooser(send, "Share log")) }
            } catch (e: Exception) {
                Log.e(tag, "log export failed", e)
                ui.post { Toast.makeText(this, "Could not export the log", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    override fun onDestroy() { io.shutdown(); super.onDestroy() }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    private class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {
        var rows: List<LogLine> = emptyList()
        var showDate = false // only needed when several days are shown together
        class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val tv = TextView(p.context).apply {
                typeface = Typeface.MONOSPACE; textSize = 12f
                setLineSpacing(0f, 1.1f)
                layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                setTextIsSelectable(true)
            }
            return VH(tv)
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, i: Int) {
            val l = rows[i]
            val tv = h.tv
            val d = tv.resources.displayMetrics.density
            if (l.isBreak) {
                val session = l.raw.startsWith(LogLine.SESSION_MARK)
                tv.setPadding(0, ((if (session) 18 else 12) * d).toInt(), 0, (4 * d).toInt())
                tv.text = l.raw
                tv.setTextColor(Color.parseColor(if (session) "#E8A33D" else "#8B7CF6"))
                tv.textSize = if (session) 12f else 10f
                tv.setTypeface(Typeface.MONOSPACE, if (session) Typeface.BOLD else Typeface.NORMAL)
                return
            }
            tv.setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
            tv.textSize = 12f
            tv.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            val levelColor = Color.parseColor(when (l.level) {
                'E', 'F' -> "#E5604D"; 'W' -> "#E8A33D"; 'I' -> "#B9B2F7"; else -> "#6E6E78"
            })
            val bodyColor = if (l.level in "WEF") levelColor else Color.parseColor(if (l.level == 'I') "#E6E6EA" else "#8A8A93")
            val sb = SpannableStringBuilder()
            fun add(s: String, color: Int, bold: Boolean = false) {
                val start = sb.length
                sb.append(s)
                sb.setSpan(ForegroundColorSpan(color), start, sb.length, 0)
                if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, 0)
            }
            add((if (showDate) l.time else l.time.substring(6)) + "  ", Color.parseColor("#6E6E78"))
            add("${l.level}  ", levelColor, bold = true)
            add((if (l.tag == "DriveBuddy") "" else "${l.tag}: ") + l.message, bodyColor)
            tv.text = sb
        }
    }
}