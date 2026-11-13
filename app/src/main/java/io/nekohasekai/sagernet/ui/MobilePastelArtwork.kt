package app.rawline.mobile.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import app.rawline.mobile.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Pastel's connect control. The disc sits in the middle of its view; the caption under it is a real text row.
 * Idle is a white disc with a soft shadow, connected is the green button with two pale rings.
 */
class MobilePastelArtwork(context: Context, @Suppress("unused") private val idleText: String) : MobilePowerArtwork(0) {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val tick = Runnable { invalidateSelf() }
    private var lit = 0f
    private var spin = 0f
    private var angle = 0f
    private var breathe = 0f
    private var lastFrame = 0L
    private val green = Color.rgb(0x34, 0xC7, 0x59)

    override var connecting = false
        set(value) { if (field != value) { field = value; invalidateSelf() } }

    private fun moving() = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()

    private fun ease(value: Float, target: Float, dt: Float): Float {
        val next = value + (target - value) * (1f - exp(-dt / 240f))
        return if (abs(next - target) < .004f) target else next
    }

    override fun draw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 8f else (now - lastFrame).coerceIn(1, 100).toFloat()
        lastFrame = now
        val litTo = if (connected || connecting) 1f else 0f
        val spinTo = if (connecting) 1f else 0f
        if (moving()) {
            lit = ease(lit, litTo, dt)
            spin = ease(spin, spinTo, dt)
            if (spin > .01f) angle = (angle + dt * .28f) % 360f
        } else {
            lit = litTo
            spin = spinTo
        }

        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        // The outer halo is the widest mark. It has to stay inside this view, or the card slices it flat.
        val room = minOf(bounds.width(), bounds.height()) / 2f - 2f * density
        val pulse = connected && !connecting
        if (moving() && pulse) breathe = (breathe + dt * 0.0032f) % (PI.toFloat() * 2f)
        val breatheScale = if (pulse && moving()) 0.90f + 0.10f * ((sin(breathe) + 1f) * 0.5f) else 1f
        val base = minOf(bounds.width() * .30f, bounds.height() * .28f, room / 1.62f) * pressScale
        val day = MobilePalette.light
        val fill = ColorUtils.blendARGB(Color.WHITE, green, lit)
        val icon = ColorUtils.blendARGB(Color.rgb(0x5C, 0x58, 0x78), Color.WHITE, lit)

        paint.style = Paint.Style.FILL
        paint.shader = null
        if (lit < .98f) {
            paint.color = Color.argb(if (day) ((28 * (1f - lit)).toInt()) else ((50 * (1f - lit)).toInt()), 80, 60, 120)
            canvas.drawCircle(cx, cy + base * .12f, base * .96f, paint)
        }
        if (lit > .01f) {
            paint.color = Color.argb((34f * lit).toInt(), 0x34, 0xC7, 0x59)
            canvas.drawCircle(cx, cy, base * 1.62f * breatheScale, paint)
            paint.color = Color.argb((70f * lit).toInt(), 0x3D, 0xD6, 0x6A)
            canvas.drawCircle(cx, cy, base * 1.32f * breatheScale, paint)
        }
        paint.color = fill
        canvas.drawCircle(cx, cy, base, paint)
        if (lit < .98f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            paint.color = Color.argb(((1f - lit) * 40).toInt(), 0x6E, 0x6A, 0x88)
            canvas.drawCircle(cx, cy, base - density, paint)
            paint.style = Paint.Style.FILL
        }

        paint.style = Paint.Style.STROKE
        if (focused) {
            paint.color = MobilePalette.text
            paint.strokeWidth = 2f * density
            canvas.drawCircle(cx, cy, base * 1.16f, paint)
        }
        if (spin > .01f) {
            canvas.save()
            canvas.rotate(angle, cx, cy)
            paint.strokeWidth = 3f * density
            paint.strokeCap = Paint.Cap.ROUND
            paint.shader = SweepGradient(cx, cy, intArrayOf(Color.TRANSPARENT, Color.WHITE, Color.TRANSPARENT), floatArrayOf(0f, .62f, .63f))
            paint.alpha = (255f * spin).toInt()
            val ring = base * 1.12f
            box.set(cx - ring, cy - ring, cx + ring, cy + ring)
            canvas.drawArc(box, 0f, 220f, false, paint)
            canvas.restore()
            paint.shader = null
            paint.alpha = 255
        }

        val glyph = base * .36f
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = base * .075f
        paint.color = icon
        box.set(cx - glyph, cy - glyph, cx + glyph, cy + glyph)
        canvas.drawArc(box, -55f, 290f, false, paint)
        canvas.drawLine(cx, cy - glyph * 1.28f, cx, cy - glyph * .12f, paint)

        val settled = abs(lit - litTo) < .004f && spin < .01f && !pulse
        if (moving() && !settled) {
            unscheduleSelf(tick)
            val windowFocused = (callback as? android.view.View)?.hasWindowFocus() != false
            scheduleSelf(tick, now + MobileOrbArtwork.frameDelay(now, windowFocused))
        }
    }
}

/** The hero card. Waves advance only while this view is on screen and its window has focus. */
class PastelHeroCard(context: Context) : LinearLayout(context) {
    private val paper = PastelHeroPaper()

    init {
        orientation = VERTICAL
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        background = paper
        clipToOutline = true
    }

    private fun sync() {
        paper.setActive(isShown && hasWindowFocus())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sync()
    }

    override fun onDetachedFromWindow() {
        paper.setActive(false)
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        sync()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        sync()
    }
}

/** Solid paper, with slow waves drifting across the lower half. */
class PastelHeroPaper : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val density get() = android.content.res.Resources.getSystem().displayMetrics.density
    private var phase = 0f
    private var posted = false
    private var active = false
    private val tick = Runnable {
        posted = false
        if (!active) return@Runnable
        phase += 0.04f
        invalidateSelf()
    }

    fun setActive(next: Boolean) {
        if (active == next) return
        active = next
        if (next) arm() else {
            unscheduleSelf(tick)
            posted = false
        }
    }

    private fun arm() {
        if (!active || posted || callback == null) return
        if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) return
        posted = true
        scheduleSelf(tick, SystemClock.uptimeMillis() + 32L)
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val radius = 28f * density
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = when {
            MobilePalette.light -> Color.WHITE
            MobilePalette.oled -> Color.rgb(16, 14, 22)
            else -> Color.rgb(42, 36, 62)
        }
        canvas.drawRoundRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), radius, radius, paint)
        val left = bounds.left.toFloat()
        val right = bounds.right.toFloat()
        val top = bounds.top.toFloat()
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        val light = MobilePalette.light
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f * density
        fun wave(y: Float, lift: Float, color: Int, shift: Float) {
            val drift = sin(phase + shift) * width * 0.05f
            val bob = sin(phase * 0.65f + shift) * height * 0.012f
            path.rewind()
            path.moveTo(left - 24f, top + height * y + bob)
            path.cubicTo(
                left + width * .28f + drift, top + height * (y - lift) + bob,
                left + width * .62f - drift, top + height * (y + lift) - bob,
                right + 24f, top + height * (y - lift * .3f) + bob
            )
            paint.color = color
            canvas.drawPath(path, paint)
        }
        wave(.58f, .07f, Color.argb(if (light) 40 else 32, 186, 170, 240), 0f)
        wave(.70f, .05f, Color.argb(if (light) 30 else 24, 150, 180, 245), 1.5f)
        wave(.82f, .06f, Color.argb(if (light) 24 else 18, 255, 186, 170), 2.8f)
        if (active) arm()
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, 28f * density)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Four rising bars. The count comes from a measured latency, never a fixed picture. */
class PastelBars(context: Context) : View(context) {
    var level = 0
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val gap = width / 11f
        val bar = (width - gap * 3f) / 4f
        for (index in 0 until 4) {
            val barHeight = height * (.38f + .18f * index)
            val left = index * (bar + gap)
            paint.color = if (index < level) Color.rgb(0x34, 0xC7, 0x59) else Color.argb(48, 140, 136, 160)
            canvas.drawRoundRect(left, height - barHeight, left + bar, height.toFloat(), bar / 3f, bar / 3f, paint)
        }
    }
}

/** The round mark at the end of a server row: a lilac check when it is the one in use. */
class PastelChoice(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lilac = Color.rgb(0x7C, 0x6B, 0xF2)

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) - paint.strokeWidth
        if (isSelected) {
            paint.style = Paint.Style.FILL
            paint.color = lilac
            canvas.drawCircle(cx, cy, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = radius * .22f
            paint.color = Color.WHITE
            canvas.drawLine(cx - radius * .38f, cy + radius * .02f, cx - radius * .08f, cy + radius * .32f, paint)
            canvas.drawLine(cx - radius * .08f, cy + radius * .32f, cx + radius * .42f, cy - radius * .28f, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = resources.displayMetrics.density * 1.4f
            paint.color = Color.argb(90, 120, 116, 145)
            canvas.drawCircle(cx, cy, radius - paint.strokeWidth, paint)
        }
    }
}

/** A filled wave along the bottom of a speed card. It moves at one pace while the VPN is up, and holds still otherwise. */
class PastelSpeedWave(context: Context, private val tint: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var phase = 0f
    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!moving()) return
            phase += 0.08f
            invalidate()
            postDelayed(this, 32L)
        }
    }
    var connected = false
        set(value) {
            if (field == value) return
            field = value
            removeCallbacks(tick)
            if (value) kick() else invalidate()
        }

    /** The wave holds still while the card is covered or the app is in the background. */
    private fun moving() = connected && isShown && hasWindowFocus()

    private fun kick() {
        removeCallbacks(tick)
        if (moving()) post(tick)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        kick()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        kick()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        kick()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val amp = height * if (connected) 0.24f else 0.08f
        fun crest(shift: Float, alpha: Int) {
            path.rewind()
            val mid = height * 0.58f
            path.moveTo(0f, height.toFloat())
            var x = 0f
            while (x <= width) {
                path.lineTo(x, mid + amp * sin(x / width * 6.2f + phase + shift))
                x += 3f
            }
            path.lineTo(width.toFloat(), height.toFloat())
            path.close()
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(alpha, Color.red(tint), Color.green(tint), Color.blue(tint))
            canvas.drawPath(path, paint)
        }
        crest(0f, if (MobilePalette.light) 56 else 78)
        crest(1.8f, if (MobilePalette.light) 32 else 46)
    }
}
