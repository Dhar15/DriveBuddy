package app.drivebuddy.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** A minimal "sliders" icon for the settings button. */
class SettingsIconView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A919E.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.8f * d
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE6E8EC.toInt() }

    init { contentDescription = "Settings" }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val left = w * 0.27f
        val right = w * 0.73f
        val top = h * 0.40f
        val bottom = h * 0.60f
        canvas.drawLine(left, top, right, top, line)
        canvas.drawLine(left, bottom, right, bottom, line)
        canvas.drawCircle(w * 0.58f, top, 3.2f * d, knob)
        canvas.drawCircle(w * 0.42f, bottom, 3.2f * d, knob)
    }
}