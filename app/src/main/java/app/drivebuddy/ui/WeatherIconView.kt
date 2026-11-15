package app.drivebuddy.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** A small weather icon drawn from the WMO weather code, no image files. */
class WeatherIconView(context: Context, private val code: Int, private val day: Boolean) : View(context) {

    private enum class Kind { CLEAR, PARTLY, CLOUD, RAIN, SNOW, STORM }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val kind = when (code) {
        0, 1 -> Kind.CLEAR
        2 -> Kind.PARTLY
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> Kind.RAIN
        71, 73, 75, 77, 85, 86 -> Kind.SNOW
        95, 96, 99 -> Kind.STORM
        else -> Kind.CLOUD
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        when (kind) {
            Kind.CLEAR -> sunOrMoon(canvas, w * 0.5f, w * 0.5f, w * 0.2f, w)
            Kind.PARTLY -> { sunOrMoon(canvas, w * 0.36f, w * 0.36f, w * 0.14f, w * 0.8f); cloud(canvas, w, 0.04f) }
            Kind.CLOUD -> cloud(canvas, w, 0f)
            Kind.RAIN -> { cloud(canvas, w, -0.1f); drops(canvas, w) }
            Kind.SNOW -> { cloud(canvas, w, -0.1f); flakes(canvas, w) }
            Kind.STORM -> { cloud(canvas, w, -0.1f, 0xFFA9A5C0.toInt()); bolt(canvas, w) }
        }
    }

    private fun sunOrMoon(c: Canvas, cx: Float, cy: Float, r: Float, size: Float) {
        if (day) {
            paint.style = Paint.Style.FILL; paint.color = 0xFFF2A93B.toInt()
            c.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = size * 0.04f; paint.strokeCap = Paint.Cap.ROUND
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val (x1, y1) = cx + Math.cos(a).toFloat() * r * 1.5f to cy + Math.sin(a).toFloat() * r * 1.5f
                val (x2, y2) = cx + Math.cos(a).toFloat() * r * 1.9f to cy + Math.sin(a).toFloat() * r * 1.9f
                c.drawLine(x1, y1, x2, y2, paint)
            }
        } else {
            val moon = Path().apply { addCircle(cx, cy, r * 1.1f, Path.Direction.CW) }
            val bite = Path().apply { addCircle(cx + r * 0.55f, cy - r * 0.35f, r * 0.95f, Path.Direction.CW) }
            moon.op(bite, Path.Op.DIFFERENCE)
            paint.style = Paint.Style.FILL; paint.color = 0xFFD9D4EE.toInt()
            c.drawPath(moon, paint)
        }
    }

    private fun cloud(c: Canvas, w: Float, dy: Float, color: Int = 0xFFD6D3E3.toInt()) {
        paint.style = Paint.Style.FILL; paint.color = color
        val y = dy * w
        c.drawRoundRect(w * 0.16f, w * 0.52f + y, w * 0.84f, w * 0.74f + y, w * 0.11f, w * 0.11f, paint)
        c.drawCircle(w * 0.38f, w * 0.5f + y, w * 0.16f, paint)
        c.drawCircle(w * 0.58f, w * 0.43f + y, w * 0.21f, paint)
    }

    private fun drops(c: Canvas, w: Float) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = w * 0.05f; paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xFF8F7BF0.toInt()
        for (x in listOf(0.32f, 0.5f, 0.68f)) c.drawLine(w * x, w * 0.74f, w * (x - 0.05f), w * 0.9f, paint)
    }

    private fun flakes(c: Canvas, w: Float) {
        paint.style = Paint.Style.FILL; paint.color = 0xFFF4F1FA.toInt()
        for (x in listOf(0.32f, 0.5f, 0.68f)) c.drawCircle(w * x, w * 0.84f, w * 0.03f, paint)
    }

    private fun bolt(c: Canvas, w: Float) {
        val p = Path().apply {
            moveTo(w * 0.54f, w * 0.6f); lineTo(w * 0.4f, w * 0.82f); lineTo(w * 0.5f, w * 0.82f)
            lineTo(w * 0.44f, w * 0.96f); lineTo(w * 0.64f, w * 0.72f); lineTo(w * 0.53f, w * 0.72f); close()
        }
        paint.style = Paint.Style.FILL; paint.color = 0xFFF2A93B.toInt()
        c.drawPath(p, paint)
    }
}