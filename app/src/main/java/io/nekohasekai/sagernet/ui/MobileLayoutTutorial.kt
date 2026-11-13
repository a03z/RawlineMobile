package app.rawline.mobile.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import app.rawline.mobile.R
import java.util.Locale

/**
 * The layout editor's tutorial: each gesture played by a finger on a small home screen drawn with the app's own
 * tiles, button, switches and servers, one short line under it. It lies over the editor, dimmed but see-through;
 * skipped or finished, [onClose] runs.
 */
class MobileLayoutTutorial(context: Context, private val onClose: () -> Unit) : FrameLayout(context) {
    private enum class Step(val caption: Int) {
        DRAG(R.string.qmobile_layout_step_drag), DOUBLE(R.string.qmobile_layout_step_double), PINCH(R.string.qmobile_layout_step_pinch),
        BADGES(R.string.qmobile_layout_step_badges), REMOVE(R.string.qmobile_layout_step_remove), SCROLL(R.string.qmobile_layout_step_scroll)
    }

    private fun dp(n: Float) = n * resources.displayMetrics.density
    private var step = 0
    private val demo = Demo(context)
    private val caption = TextView(context).apply {
        setTextColor(Color.WHITE); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setPadding(dp(24f).toInt(), dp(18f).toInt(), dp(24f).toInt(), dp(8f).toInt())
    }
    private val dots = LinearLayout(context).apply { gravity = Gravity.CENTER }
    private val next = TextView(context)

    init {
        setBackgroundColor(Color.argb(200, 0, 0, 0))
        isClickable = true; isFocusable = true
        alpha = 0f; animate().alpha(1f).setDuration(220).start()
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val metrics = resources.displayMetrics
        val width = minOf(dp(320f).toInt(), metrics.widthPixels - dp(48f).toInt(), ((metrics.heightPixels - dp(260f)) / 1.2f).toInt())
        column.addView(demo, LinearLayout.LayoutParams(width, (width * 1.2f).toInt()))
        column.addView(caption, LinearLayout.LayoutParams(-1, -2))
        column.addView(dots, LinearLayout.LayoutParams(-1, dp(22f).toInt()))
        val buttons = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(32f).toInt(), dp(14f).toInt(), dp(32f).toInt(), 0) }
        buttons.addView(TextView(context).apply {
            text = context.getString(R.string.qmobile_layout_tutorial_skip); textSize = 15f
            setTextColor(ColorUtils.setAlphaComponent(Color.WHITE, 200)); setPadding(dp(8f).toInt(), dp(12f).toInt(), dp(8f).toInt(), dp(12f).toInt())
            setOnClickListener { close() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val accent = MobilePalette.accent(context)
        next.apply {
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(contrastOn(accent)); gravity = Gravity.CENTER
            setPadding(dp(26f).toInt(), dp(11f).toInt(), dp(26f).toInt(), dp(11f).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { setColor(accent); cornerRadius = dp(22f) }
            setOnClickListener { if (step == Step.values().lastIndex) close() else show(step + 1) }
        }
        buttons.addView(next, LinearLayout.LayoutParams(-2, -2))
        column.addView(buttons, LinearLayout.LayoutParams(-1, -2))
        addView(column, LayoutParams(-1, -2, Gravity.CENTER))
        show(0)
    }

    private fun show(index: Int) {
        step = index
        val current = Step.values()[index]
        caption.text = context.getString(current.caption)
        caption.alpha = 0f; caption.animate().alpha(1f).setDuration(180).start()
        next.text = context.getString(if (index == Step.values().lastIndex) R.string.qmobile_layout_tutorial_done else R.string.qmobile_layout_tutorial_next)
        dots.removeAllViews()
        Step.values().indices.forEach { i ->
            dots.addView(View(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (i == index) Color.WHITE else ColorUtils.setAlphaComponent(Color.WHITE, 90))
                }
            }, LinearLayout.LayoutParams(dp(7f).toInt(), dp(7f).toInt()).apply { marginStart = dp(4f).toInt(); marginEnd = dp(4f).toInt() })
        }
        demo.step = current
    }

    fun close() {
        animate().alpha(0f).setDuration(180).withEndAction { (parent as? ViewGroup)?.removeView(this); onClose() }.start()
    }

    override fun onDetachedFromWindow() {
        demo.stop()
        super.onDetachedFromWindow()
    }

    /** A small home screen and a finger playing [step] over and over. */
    private inner class Demo(context: Context) : View(context) {
        var step = Step.DRAG
            set(value) { field = value; invalidate() }
        private var t = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dash = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
        private val box = RectF()
        private val accent = MobilePalette.accent(context)
        private val orb = MobileOrbArtwork(context, context.getString(R.string.qmobile_orb_idle))
        private val flags = listOf("de", "fi", "nl", "se", "us", "jp", "gb")
        private val clock = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
        }
        private lateinit var c: Canvas

        override fun onAttachedToWindow() { super.onAttachedToWindow(); clock.start() }
        fun stop() = clock.cancel()

        private fun span(from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)
        private fun ease(x: Float) = x * x * (3 - 2 * x)
        private fun lerp(a: Float, b: Float, x: Float) = a + (b - a) * x
        private fun pulse(at: Float) = span(at, at + .05f) * (1 - span(at + .05f, at + .1f))

        /** A surface of the app's own colour; [fade] multiplies its own alpha, so see-through colours stay see-through. */
        private fun surface(left: Float, top: Float, right: Float, bottom: Float, color: Int = MobilePalette.surface, fade: Float = 1f, radius: Float = dp(10f)) {
            box.set(left, top, right, bottom)
            paint.style = Paint.Style.FILL; paint.pathEffect = null
            paint.color = ColorUtils.setAlphaComponent(color, (Color.alpha(color) * fade).toInt())
            c.drawRoundRect(box, radius, radius, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f)
            paint.color = ColorUtils.setAlphaComponent(MobilePalette.outline, (Color.alpha(MobilePalette.outline) * fade).toInt())
            c.drawRoundRect(box, radius, radius, paint)
        }

        private fun outline(left: Float, top: Float, right: Float, bottom: Float) {
            box.set(left, top, right, bottom)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.6f); paint.pathEffect = dash
            paint.color = ColorUtils.setAlphaComponent(MobilePalette.text, (120 + 135 * kotlin.math.abs(kotlin.math.sin(t * 6.283f * 2))).toInt())
            c.drawRoundRect(box, dp(12f), dp(12f), paint)
            paint.pathEffect = null
        }

        private fun icon(res: Int, cx: Float, cy: Float, size: Float, color: Int, fade: Float = 1f) {
            val drawable = ContextCompat.getDrawable(context, res)?.mutate() ?: return
            drawable.setTint(color); drawable.alpha = (255 * fade).toInt()
            drawable.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
            drawable.draw(c)
        }

        private fun badge(res: Int, cx: Float, cy: Float, fill: Int, pressed: Float = 0f) {
            paint.style = Paint.Style.FILL; paint.color = fill
            c.drawCircle(cx, cy, dp(10f) * (1 - .12f * pressed), paint)
            icon(res, cx, cy, dp(13f), contrastOn(fill))
        }

        private fun text(value: String, x: Float, y: Float, size: Float, color: Int = MobilePalette.text, bold: Boolean = false) {
            paint.style = Paint.Style.FILL; paint.color = color; paint.textSize = size; paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.textAlign = Paint.Align.LEFT
            c.drawText(value, x, y, paint)
        }

        private fun finger(x: Float, y: Float, press: Float, visible: Float) {
            if (visible <= 0f) return
            paint.style = Paint.Style.FILL
            paint.color = ColorUtils.setAlphaComponent(Color.WHITE, (80 * visible * press).toInt())
            c.drawCircle(x, y, dp(24f), paint)
            paint.color = ColorUtils.setAlphaComponent(Color.BLACK, (60 * visible).toInt())
            c.drawCircle(x, y + dp(2f), dp(13f), paint)
            paint.color = ColorUtils.setAlphaComponent(Color.WHITE, (235 * visible).toInt())
            c.drawCircle(x, y, dp(lerp(13f, 10f, press)), paint)
        }

        override fun onDraw(canvas: Canvas) {
            c = canvas
            val w = width.toFloat(); val h = height.toFloat()
            val pad = dp(12f)
            box.set(0f, 0f, w, h)
            paint.style = Paint.Style.FILL; paint.pathEffect = null; paint.color = MobilePalette.background
            canvas.drawRoundRect(box, dp(22f), dp(22f), paint)
            canvas.save(); canvas.clipRect(0f, 0f, w, h)
            val tile = (w - 2 * pad - dp(6f) * 4) / 5
            val tilesTop = pad
            fun tiles(y: Float, removed: Float = 0f, minus: Boolean = false) {
                val icons = listOf(R.drawable.ic_mobile_settings, R.drawable.ic_mobile_tun, R.drawable.ic_mobile_route, R.drawable.ic_mobile_subscriptions, R.drawable.ic_mobile_update)
                icons.forEachIndexed { i, res ->
                    val left = pad + i * (tile + dp(6f))
                    val fade = if (i == 3) 1 - .7f * removed else 1f
                    surface(left, y, left + tile, y + tile, fade = fade)
                    icon(res, left + tile / 2, y + tile / 2, tile * .45f, MobilePalette.text, fade)
                    if (minus && i == 3) badge(if (removed > .5f) R.drawable.ic_mobile_add else R.drawable.ic_layout_remove, left + tile - dp(8f), y + dp(8f),
                        if (removed > .5f) accent else Color.rgb(220, 66, 61))
                }
            }
            val chipTop = tilesTop + tile + dp(8f)
            fun chip(y: Float) {
                surface(pad, y, w - pad - dp(40f), y + dp(34f))
                text(context.getString(R.string.subscription), pad + dp(12f), y + dp(22f), dp(13f), bold = true)
                surface(w - pad - dp(34f), y, w - pad, y + dp(34f))
                icon(R.drawable.ic_mobile_add, w - pad - dp(17f), y + dp(17f), dp(16f), MobilePalette.text)
            }
            fun rows(top: Float) {
                var y = top
                var i = 0
                while (y < h) {
                    surface(pad, y, w - pad, y + dp(34f))
                    MobileCountryFlags.bitmap(context, flags[i % flags.size])?.let { flag ->
                        box.set(pad + dp(10f), y + dp(10f), pad + dp(32f), y + dp(24f))
                        c.drawBitmap(flag, null, box, paint)
                    }
                    text(Locale("", flags[i % flags.size]).displayCountry, pad + dp(42f), y + dp(22f), dp(12f))
                    y += dp(40f); i++
                }
            }
            fun drawOrb(cx: Float, top: Float, size: Float) {
                orb.setBounds((cx - size / 2).toInt(), top.toInt(), (cx + size / 2).toInt(), (top + size).toInt())
                orb.draw(c)
            }
            fun switches(top: Float, fade: Float) {
                val mid = w / 2
                listOf(R.string.service_mode_vpn to pad, R.string.service_mode_proxy to mid + dp(4f)).forEach { (label, left) ->
                    val right = if (left == pad) mid - dp(4f) else w - pad
                    surface(left, top, right, top + dp(36f), fade = fade)
                    text(context.getString(label), left + dp(10f), top + dp(23f), dp(11f), ColorUtils.setAlphaComponent(MobilePalette.text, (255 * fade).toInt()))
                    paint.color = ColorUtils.setAlphaComponent(MobilePalette.muted, (255 * fade).toInt()); paint.style = Paint.Style.FILL
                    box.set(right - dp(34f), top + dp(11f), right - dp(10f), top + dp(25f)); c.drawRoundRect(box, dp(7f), dp(7f), paint)
                }
            }
            val controlTop = chipTop + dp(42f)
            val orbSize = dp(110f)
            val controlBottom = controlTop + orbSize + dp(30f)
            val cx = w / 2
            when (step) {
                Step.DRAG -> {
                    tiles(tilesTop); chip(chipTop); rows(controlTop)
                    val move = ease(span(.25f, .7f)); val down = lerp(0f, h - controlBottom - pad, move)
                    val held = span(.12f, .25f) * (1 - span(.78f, .88f))
                    surface(pad - dp(2f), controlTop + down, w - pad + dp(2f), controlBottom + down, MobilePalette.background, radius = dp(12f))
                    drawOrb(cx, controlTop + dp(26f) + down, orbSize)
                    outline(pad - dp(4f), controlTop - dp(2f) + down, w - pad + dp(4f), controlBottom + down)
                    finger(cx, controlTop + dp(26f) + orbSize / 2 + down, held, span(.02f, .12f) * (1 - span(.88f, .98f)))
                }
                Step.DOUBLE -> {
                    tiles(tilesTop); chip(chipTop)
                    val morph = ease(span(.48f, .6f))
                    val bottom = lerp(controlBottom, controlTop + dp(80f), morph)
                    rows(bottom + dp(8f))
                    outline(pad - dp(4f), controlTop - dp(2f), w - pad + dp(4f), bottom)
                    if (morph < 1f) { c.saveLayerAlpha(0f, 0f, w, h, (255 * (1 - morph)).toInt()); drawOrb(cx, controlTop + dp(26f), orbSize); c.restore() }
                    switches(controlTop + dp(30f), morph)
                    val tap = pulse(.2f) + pulse(.34f)
                    finger(cx, controlTop + dp(26f) + orbSize / 2, tap, span(.05f, .15f) * (1 - span(.85f, .95f)))
                }
                Step.PINCH -> {
                    tiles(tilesTop); chip(chipTop)
                    val squeeze = ease(span(.3f, .65f))
                    val size = lerp(orbSize, orbSize * .72f, squeeze)
                    val bottom = controlTop + size + dp(30f)
                    rows(bottom + dp(8f))
                    outline(pad - dp(4f), controlTop - dp(2f), w - pad + dp(4f), bottom)
                    drawOrb(cx, controlTop + dp(26f), size)
                    val gap = lerp(dp(70f), dp(26f), ease(span(.25f, .65f)))
                    val cy = controlTop + dp(26f) + size / 2
                    val visible = span(.08f, .2f) * (1 - span(.8f, .9f))
                    finger(cx - gap, cy - gap * .5f, .6f, visible); finger(cx + gap, cy + gap * .5f, .6f, visible)
                }
                Step.BADGES -> {
                    tiles(tilesTop); chip(chipTop)
                    val small = ease(span(.5f, .62f))
                    val size = lerp(orbSize, orbSize * .72f, small)
                    val bottom = controlTop + size + dp(30f)
                    rows(bottom + dp(8f))
                    outline(pad - dp(4f), controlTop - dp(2f), w - pad + dp(4f), bottom)
                    drawOrb(cx, controlTop + dp(26f), size)
                    val by = controlTop + dp(12f)
                    badge(R.drawable.ic_layout_scroll, w - pad - dp(64f), by, MobilePalette.well)
                    badge(R.drawable.ic_layout_swap, w - pad - dp(40f), by, accent)
                    badge(if (small > .5f) R.drawable.ic_layout_grow else R.drawable.ic_layout_shrink, w - pad - dp(16f), by, accent, pulse(.42f))
                    finger(w - pad - dp(16f), by, pulse(.42f), span(.1f, .25f) * (1 - span(.85f, .95f)))
                }
                Step.REMOVE -> {
                    val gone = ease(span(.3f, .42f)) * (1 - ease(span(.72f, .84f)))
                    tiles(tilesTop, gone, minus = true); chip(chipTop); rows(controlBottom + dp(8f))
                    outline(pad - dp(4f), tilesTop - dp(4f), w - pad + dp(4f), tilesTop + tile + dp(4f))
                    drawOrb(cx, controlTop + dp(26f), orbSize)
                    val bx = pad + 3 * (tile + dp(6f)) + tile - dp(8f); val by = tilesTop + dp(8f)
                    finger(bx, by, pulse(.22f) + pulse(.62f), span(.08f, .2f) * (1 - span(.86f, .96f)))
                }
                Step.SCROLL -> {
                    // The scroll button on the block lights up, then the block leaves with the servers.
                    val lit = span(.2f, .24f)
                    val shift = -ease(span(.4f, .78f)) * (controlBottom - chipTop) * (1 - ease(span(.88f, .98f)))
                    tiles(tilesTop); chip(chipTop)
                    c.save(); c.clipRect(0f, chipTop + dp(40f), w, h)
                    outline(pad - dp(4f), controlTop - dp(2f) + shift, w - pad + dp(4f), controlBottom + shift)
                    drawOrb(cx, controlTop + dp(26f) + shift, orbSize)
                    badge(R.drawable.ic_layout_scroll, w - pad - dp(16f), controlTop + dp(12f) + shift, if (lit > .5f) accent else MobilePalette.well, pulse(.17f))
                    rows(controlBottom + dp(8f) + shift)
                    c.restore()
                    if (t < .3f) finger(w - pad - dp(16f), controlTop + dp(12f), pulse(.17f), span(.04f, .12f) * (1 - span(.24f, .3f)))
                    else finger(cx, lerp(h * .85f, h * .85f - (controlBottom - chipTop), ease(span(.4f, .78f))), span(.34f, .4f) * (1 - span(.78f, .82f)), span(.3f, .36f) * (1 - span(.8f, .88f)))
                }
            }
            canvas.restore()
        }
    }
}
