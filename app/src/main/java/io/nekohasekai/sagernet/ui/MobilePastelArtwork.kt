package app.rawline.mobile.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import app.rawline.mobile.R
import kotlin.math.abs
import kotlin.math.exp

/**
 * Pastel's connect control: a plain disc in the same place as the wavy orb.
 * Idle stays pale, connected turns green, and connecting keeps a stroke running round the edge.
 */
class MobilePastelArtwork(context: Context, private val idleText: String) : MobilePowerArtwork(0) {
    private val density = context.resources.displayMetrics.density
    private val font = runCatching { ResourcesCompat.getFont(context, R.font.geologica) }.getOrNull() ?: Typeface.DEFAULT
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val tick = Runnable { invalidateSelf() }
    private var lit = 0f
    private var spin = 0f
    private var angle = 0f
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

        val size = minOf(bounds.width(), bounds.height())
        val cx = bounds.exactCenterX()
        val cy = bounds.top + MobileOrbArtwork.centerY(size)
        val base = MobileOrbArtwork.radius(size) * pressScale
        val day = MobilePalette.light
        // A flat disc, the same in both modes: pale while idle, green once connected. A metal gradient read as the old orb.
        val fill = ColorUtils.blendARGB(Color.WHITE, green, lit)
        val icon = ColorUtils.blendARGB(Color.rgb(0x5C, 0x58, 0x78), Color.WHITE, lit)

        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = Color.argb(if (day) 36 else 70, 70, 40, 110)
        canvas.drawCircle(cx, cy + base * .16f, base * .94f, paint)
        if (lit > .01f) {
            paint.shader = RadialGradient(cx, cy, base * 1.7f, ColorUtils.setAlphaComponent(green, (80f * lit).toInt()), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, base * 1.7f, paint)
        }
        paint.shader = null
        paint.color = fill
        canvas.drawCircle(cx, cy, base, paint)
        paint.shader = RadialGradient(cx - base * .15f, cy - base * .42f, base * .85f, Color.argb(if (day) 90 else 70, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, base, paint)
        paint.shader = null

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

        val glyph = base * .34f
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = base * .07f
        paint.color = icon
        box.set(cx - glyph, cy - glyph, cx + glyph, cy + glyph)
        canvas.drawArc(box, -55f, 290f, false, paint)
        canvas.drawLine(cx, cy - glyph * 1.28f, cx, cy - glyph * .12f, paint)

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = font
        val rest = MobileOrbArtwork.radius(size)
        val top = cy + rest + 22f * density
        paint.textSize = 13f * density
        paint.color = MobilePalette.muted
        val caption = statusText.ifEmpty { idleText }
        val width = paint.measureText(caption)
        val limit = bounds.width() * .84f
        if (width > limit && width > 0f) paint.textSize *= limit / width
        canvas.drawText(caption, cx, top, paint)
        if (connected) {
            paint.textSize = 16f * density
            paint.color = MobilePalette.text
            paint.isFakeBoldText = true
            canvas.drawText(if (workersText.isEmpty()) elapsedText else "$elapsedText   $workersText", cx, top + 25f * density, paint)
            paint.isFakeBoldText = false
        } else if (workersText.isNotEmpty()) {
            paint.textSize = 13f * density
            paint.color = MobilePalette.muted
            canvas.drawText(workersText, cx, top + 22f * density, paint)
        }

        val settled = abs(lit - litTo) < .004f && spin < .01f
        if (moving() && !settled) {
            unscheduleSelf(tick)
            val windowFocused = (callback as? android.view.View)?.hasWindowFocus() != false
            scheduleSelf(tick, now + MobileOrbArtwork.frameDelay(now, windowFocused))
        }
    }
}
