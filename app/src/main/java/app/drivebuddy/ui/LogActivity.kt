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

/** Shows the saved log with filters and sorting, and shares what is on screen. */
class LogActivity : Activity() {
    private val tag = "DriveBuddy"
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val adapter = LogAdapter()
    private var files: List<File> = emptyList() // newest first
    private var shown: List<LogLine> = emptyList()
    private var query = LogQuery()
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
            setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
            // Draw below the status bar and above the navigation bar (edge-to-edge on Android 15).
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(12.dp(), b.top + 8.dp(), 12.dp(), b.bottom + 8.dp())
                insets
            }
        }

        // Row 1: day, newest-first, share
        days = Spinner(this).apply {
            val labels = listOf("All days") + files.map { it.name.removePrefix("drivebuddy-").removeSuffix(".log") }
            adapter = ArrayAdapter(this@LogActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            if (files.isNotEmpty()) setSelection(1) // default: newest day
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = reload()
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        val newest = CheckBox(this).apply {
            text = "Newest first"; setTextColor(ink)
            setOnCheckedChangeListener { _, on -> query = query.copy(newestFirst = on); reload() }
        }
        val share = Button(this).apply { text = "Share"; setOnClickListener { share() } }
        root.addView(row(days.apply { }, newest, share))

        // Row 2: level, app only
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
        val appOnly = CheckBox(this).apply {
            text = "DriveBuddy lines only"; setTextColor(ink); isChecked = true
            setOnCheckedChangeListener { _, on -> query = query.copy(appOnly = on); reload() }
        }
        root.addView(row(levelSpinner, appOnly))

        // Row 3: search
        val search = EditText(this).apply {
            hint = "Search text (e.g. commute, TTS, mic)"
            setTextColor(ink); setHintTextColor(Color.parseColor("#6E6E78"))
            isSingleLine = true
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
        root.addView(search, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        count = TextView(this).apply { setTextColor(Color.parseColor("#8A8A93")); textSize = 12f }
        root.addView(count)

        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LogActivity)
            adapter = this@LogActivity.adapter
        }
        root.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
        Log.i(tag, "log viewer opened: ${files.size} day files")
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
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
        class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val d = p.resources.displayMetrics.density
            val tv = TextView(p.context).apply {
                typeface = Typeface.MONOSPACE; textSize = 11f
                setPadding(0, (3 * d).toInt(), 0, (3 * d).toInt())
                layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                setTextIsSelectable(true)
            }
            return VH(tv)
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, i: Int) {
            val l = rows[i]
            val tv = h.tv
            if (l.isBreak) {
                val session = l.raw.startsWith(LogLine.SESSION_MARK)
                tv.text = l.raw
                tv.setTextColor(Color.parseColor(if (session) "#E8A33D" else "#8B7CF6"))
                tv.textSize = if (session) 12f else 10f
                tv.setTypeface(Typeface.MONOSPACE, if (session) Typeface.BOLD else Typeface.NORMAL)
                return
            }
            tv.textSize = 11f
            tv.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            val tag = if (l.tag == "DriveBuddy") "" else "${l.tag}: "
            tv.text = "${l.time}  ${l.level}  $tag${l.message}"
            tv.setTextColor(Color.parseColor(when (l.level) {
                'E', 'F' -> "#E5604D"
                'W' -> "#E8A33D"
                'I' -> "#E6E6EA"
                else -> "#8A8A93"
            }))
        }
    }
}