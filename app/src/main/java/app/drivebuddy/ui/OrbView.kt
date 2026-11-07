package app.drivebuddy.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The DriveBuddy orb: a floating circle. Also the Talk button: tap it to speak.
 * Each [DriveState] has its own motion, so the state reads without any text.
 */
class OrbView(context: Context) : View(context) {

    /** How the orb looks and moves in one state. */
    private class Look(
        val breath: Float,   // size of the slow breathing swell
        val breathHz: Float, // speed of that swell
        val float: Float,    // 1 = full floating drift, lower = steadier
        val glow: Float,     // brightness of the halo
        val color: Int,
        val listen: Float,   // 1 = inward rings
        val think: Float,    // 1 = orbiting arc
        val speak: Float     // 1 = vibration + outward ripples
    )

    private val looks = mapOf(
        DriveState.OFF to Look(0.010f, 0.4f, 0.3f, 0.20f, 0xFF4A4F5C.toInt(), 0f, 0f, 0f),
        DriveState.IDLE to Look(0.035f, 0.9f, 1.0f, 0.55f, 0xFF5B3FBF.toInt(), 0f, 0f, 0f),
        DriveState.LISTENING to Look(0.030f, 1.8f, 0.6f, 0.90f, 0xFF7457DB.toInt(), 1f, 0f, 0f),
        DriveState.THINKING to Look(0.015f, 1.2f, 0.5f, 0.70f, 0xFF8B74E6.toInt(), 0f, 1f, 0f),
        DriveState.SPEAKING to Look(0.020f, 1.2f, 0.5f, 1.00f, 0xFF6A4CD0.toInt(), 0f, 0f, 1f),
        DriveState.ERROR to Look(0.015f, 0.6f, 0.4f, 0.50f, 0xFFD9604F.toInt(), 0f, 0f, 0f)
    )

    private var target: Look = looks.getValue(DriveState.OFF)

    // Current values, eased toward the target every frame so changes never snap.
    private var breath = target.breath
    private var breathHz = target.breathHz
    private var floatAmt = target.float
    private var glow = target.glow
    private var r = Color.red(target.color).toFloat()
    private var g = Color.green(target.color).toFloat()
    private var b = Color.blue(target.color).toFloat()
    private var listenW = 0f
    private var thinkW = 0f
    private var speakW = 0f

    /** Microphone loudness 0..1. Optional: lets the orb swell with your voice once the service feeds it. */
    @Volatile
    var level: Float = 0f

    private var clock = 0f       // seconds, always increasing
    private var breathPhase = 0f // advances at the current breath speed, so speed changes never jump
    private var lastNanos = 0L
    private var levelSmooth = 0f

    private val density = resources.displayMetrics.density
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val arcRect = RectF()

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { tick() }
    }

    init {
        contentDescription = "Talk to DriveBuddy"
        isClickable = true
    }

    fun setState(state: DriveState) {
        target = looks.getValue(state)
        if (!ValueAnimator.areAnimatorsEnabled()) { // reduced motion: change look, hold still
            snapToTarget()
            invalidate()
        }
    }

    private fun snapToTarget() {
        breath = 0f
        floatAmt = 0f
        glow = target.glow
        r = Color.red(target.color).toFloat()
        g = Color.green(target.color).toFloat()
        b = Color.blue(target.color).toFloat()
        listenW = target.listen
        thinkW = target.think
        speakW = target.speak
    }

    private fun tick() {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = now
        clock += dt

        val k = 1f - exp(-dt * 5f) // easing step, independent of frame rate
        breath += (target.breath - breath) * k
        breathHz += (target.breathHz - breathHz) * k
        floatAmt += (target.float - floatAmt) * k
        glow += (target.glow - glow) * k
        r += (Color.red(target.color) - r) * k
        g += (Color.green(target.color) - g) * k
        b += (Color.blue(target.color) - b) * k
        listenW += (target.listen - listenW) * k
        thinkW += (target.think - thinkW) * k
        speakW += (target.speak - speakW) * k
        levelSmooth += (level - levelSmooth) * (1f - exp(-dt * 12f))

        breathPhase += breathHz * dt
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val base = min(w, h) * 0.5f * 0.5f
        // A soft highlight in the upper left gives the circle volume. Drawn relative to the orb's centre.
        shine.shader = RadialGradient(
            -base * 0.35f, -base * 0.4f, base * 1.2f,
            intArrayOf(0x66FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val color = Color.rgb(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
        val unit = min(width, height) * 0.5f * 0.5f

        // Floating: slow drift on both axes from sine waves that never line up exactly.
        val driftX = (sin(clock * 0.50f + 1f) * 3f + sin(clock * 0.23f) * 2f) * density * floatAmt
        val driftY = (sin(clock * 0.80f) * 6f + sin(clock * 0.37f + 2f) * 3f) * density * floatAmt

        // Speaking: a quick, smooth vibration built from three unrelated frequencies.
        val voice = (sin(clock * 7.3f) * 0.5f + sin(clock * 11.9f + 1f) * 0.3f + sin(clock * 17.1f + 2f) * 0.2f)
        val swell = breath * sin(breathPhase * 6.2832f) +
                0.05f * speakW * voice + 0.12f * levelSmooth
        val radius = unit * (1f + swell)

        canvas.save()
        canvas.translate(width / 2f + driftX, height / 2f + driftY)

        // Halo: stacked translucent circles, no shaders or filters, so it costs almost nothing per frame.
        halo.color = color
        for (i in 6 downTo 1) {
            halo.alpha = (glow * 16f * (7 - i) / 6f).toInt().coerceIn(0, 255)
            canvas.drawCircle(0f, 0f, radius * (1f + i * 0.2f), halo)
        }

        if (listenW > 0.01f) { // rings drawing inward: "I'm taking your voice in"
            for (i in 0..1) {
                val p = (clock * 0.8f + i * 0.5f) % 1f
                ring.color = color
                ring.alpha = (p * 120 * listenW).toInt()
                canvas.drawCircle(0f, 0f, radius * (1.9f - 0.8f * p), ring)
            }
        }
        if (speakW > 0.01f) { // rings rippling outward
            for (i in 0..1) {
                val p = (clock * 0.7f + i * 0.5f) % 1f
                ring.color = color
                ring.alpha = ((1f - p) * 120 * speakW).toInt()
                canvas.drawCircle(0f, 0f, radius * (1.05f + 0.7f * p), ring)
            }
        }

        body.color = color
        canvas.drawCircle(0f, 0f, radius, body)
        canvas.save()
        canvas.scale(radius / unit, radius / unit) // keep the highlight sized to the circle
        canvas.drawCircle(0f, 0f, unit, shine)
        canvas.restore()

        if (thinkW > 0.01f) { // a short comet circling the orb, fading behind itself
            arc.color = color
            val rr = radius * 1.35f
            arcRect.set(-rr, -rr, rr, rr)
            val head = clock * 200f
            for (i in 0..5) {
                arc.alpha = ((1f - i / 6f) * 255 * thinkW).toInt()
                canvas.drawArc(arcRect, head - i * 14f, 14f, false, arc)
            }
        }
        canvas.restore()
    }

    // Press feedback: shrink on touch, spring back on release, with a light haptic tick.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animate().scaleX(0.94f).scaleY(0.94f).setDuration(120).setInterpolator(LinearInterpolator()).start()
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(2.2f)).start()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastNanos = 0L
        if (ValueAnimator.areAnimatorsEnabled()) ticker.start() else snapToTarget()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // Don't burn battery drawing an orb nobody can see.
        if (visibility != VISIBLE) ticker.cancel()
        else if (isAttachedToWindow && ValueAnimator.areAnimatorsEnabled() && !ticker.isRunning) {
            lastNanos = 0L
            ticker.start()
        }
    }
}