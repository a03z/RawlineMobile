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
import androidx.core.graphics.ColorUtils
import app.rawline.mobile.R

/**
 * The layout editor's tutorial: each gesture played by a finger on a small home screen, one short line under it.
 * It lies over the editor, dimmed but see-through; skipped or finished, [onClose] runs.
 */
class MobileLayoutTutorial(context: Context, private val onClose: () -> Unit) : FrameLayout(context) {
    private enum class Step(val caption: Int) {
        DRAG(R.string.qmobile_layout_step_drag), DOUBLE(R.string.qmobile_layout_step_double), BADGES(R.string.qmobile_layout_step_badges),
        REMOVE(R.string.qmobile_layout_step_remove), SCROLL(R.string.qmobile_layout_step_scroll)
    }

    private fun dp(n: Float) = n * resources.displayMetrics.density
    private var step = 0
    private val demo = Demo(context)
    private val caption = TextView(context).apply {
        setTextColor(Color.WHITE); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setPadding(dp(24f).toInt(), dp(16f).toInt(), dp(24f).toInt(), dp(8f).toInt())
    }
    private val dots = LinearLayout(context).apply { gravity = Gravity.CENTER }
    private val next = TextView(context)

    init {
        setBackgroundColor(Color.argb(185, 0, 0, 0))
        isClickable = true; isFocusable = true
        alpha = 0f; animate().alpha(1f).setDuration(220).start()
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val width = minOf(dp(300f).toInt(), resources.displayMetrics.widthPixels - dp(64f).toInt())
        column.addView(demo, LinearLayout.LayoutParams(width, (width * 1.15f).toInt()))
        column.addView(caption, LinearLayout.LayoutParams(-1, -2))
        column.addView(dots, LinearLayout.LayoutParams(-1, dp(24f).toInt()))
        val buttons = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24f).toInt(), dp(12f).toInt(), dp(24f).toInt(), 0) }
        buttons.addView(TextView(context).apply {
            text = context.getString(R.string.qmobile_layout_tutorial_skip); textSize = 15f
            setTextColor(ColorUtils.setAlphaComponent(Color.WHITE, 190)); setPadding(dp(8f).toInt(), dp(12f).toInt(), dp(8f).toInt(), dp(12f).toInt())
            setOnClickListener { close() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        next.apply {
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(22f).toInt(), dp(11f).toInt(), dp(22f).toInt(), dp(11f).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { setColor(MobilePalette.accent(context)); cornerRadius = dp(22f) }
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
            set(value) { field = value; t = 0f; invalidate() }
        private var t = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dash = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
        private val box = RectF()
        private val accent = MobilePalette.accent(context)
        private val clock = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2800; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); clock.start() }
        fun stop() = clock.cancel()

        private fun span(from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)
        private fun ease(x: Float) = x * x * (3 - 2 * x)
        private fun lerp(a: Float, b: Float, x: Float) = a + (b - a) * x

        private fun block(left: Float, top: Float, right: Float, bottom: Float, fill: Int, outline: Boolean = true, alpha: Int = 255) {
            box.set(left, top, right, bottom)
            paint.style = Paint.Style.FILL; paint.color = ColorUtils.setAlphaComponent(fill, alpha); paint.pathEffect = null
            canvas?.drawRoundRect(box, dp(10f), dp(10f), paint)
            if (outline) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.6f); paint.pathEffect = dash
                paint.color = ColorUtils.setAlphaComponent(accent, (alpha * (.45f + .55f * kotlin.math.abs(kotlin.math.sin(t * 6.283f * 2)))).toInt())
                canvas?.drawRoundRect(box, dp(10f), dp(10f), paint)
                paint.pathEffect = null
            }
        }

        private fun badge(cx: Float, cy: Float, minus: Boolean = false, pressed: Boolean = false) {
            paint.style = Paint.Style.FILL; paint.color = if (minus) Color.rgb(220, 66, 61) else accent
            canvas?.drawCircle(cx, cy, dp(if (pressed) 8f else 9f), paint)
            paint.color = Color.WHITE; paint.strokeWidth = dp(1.8f); paint.style = Paint.Style.STROKE
            canvas?.drawLine(cx - dp(4f), cy, cx + dp(4f), cy, paint)
            if (!minus) canvas?.drawLine(cx, cy - dp(4f), cx, cy + dp(4f), paint)
        }

        private fun finger(x: Float, y: Float, press: Float, visible: Float) {
            if (visible <= 0f) return
            paint.style = Paint.Style.FILL
            paint.color = ColorUtils.setAlphaComponent(Color.WHITE, (90 * visible * press).toInt())
            canvas?.drawCircle(x, y, dp(26f), paint)
            paint.color = ColorUtils.setAlphaComponent(Color.WHITE, (230 * visible).toInt())
            canvas?.drawCircle(x, y, dp(lerp(14f, 11f, press)), paint)
        }

        private var canvas: Canvas? = null

        override fun onDraw(c: Canvas) {
            canvas = c
            val w = width.toFloat(); val h = height.toFloat()
            val pad = dp(14f)
            box.set(0f, 0f, w, h)
            paint.style = Paint.Style.FILL; paint.pathEffect = null; paint.color = MobilePalette.background
            c.drawRoundRect(box, dp(22f), dp(22f), paint)
            val surface = MobilePalette.surface
            val tilesTop = pad; val tilesBottom = pad + dp(36f)
            val controlTop = tilesBottom + dp(10f); val controlBottom = controlTop + dp(96f)
            val listTop = controlBottom + dp(12f)
            fun rows(offset: Float) {
                var y = listTop + offset
                while (y < h - pad) {
                    if (y + dp(30f) > listTop - dp(1f)) block(pad, y, w - pad, y + dp(30f), surface, outline = false, alpha = 200)
                    y += dp(38f)
                }
            }
            fun tiles(offset: Float = 0f, removed: Float = 0f) {
                val size = (w - 2 * pad - dp(8f) * 4) / 5
                for (i in 0 until 5) {
                    val left = pad + i * (size + dp(8f))
                    if (i == 3 && removed > 0f) {
                        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.6f); paint.pathEffect = dash; paint.color = ColorUtils.setAlphaComponent(Color.WHITE, (160 * removed).toInt())
                        box.set(left, tilesTop + offset, left + size, tilesTop + offset + dp(36f)); c.drawRoundRect(box, dp(9f), dp(9f), paint); paint.pathEffect = null
                        block(left, tilesTop + offset, left + size, tilesTop + offset + dp(36f), surface, outline = false, alpha = (255 * (1 - removed)).toInt())
                    } else block(left, tilesTop + offset, left + size, tilesTop + offset + dp(36f), surface, outline = false)
                }
            }
            fun orb(cx: Float, cy: Float, r: Float, alpha: Int = 255) {
                paint.style = Paint.Style.FILL; paint.color = ColorUtils.setAlphaComponent(surface, alpha); c.drawCircle(cx, cy, r, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2f); paint.color = ColorUtils.setAlphaComponent(accent, alpha); c.drawCircle(cx, cy, r * .82f, paint)
            }
            fun switches(alpha: Int) {
                val mid = w / 2
                block(pad, controlTop + dp(28f), mid - dp(4f), controlTop + dp(68f), surface, outline = false, alpha = alpha)
                block(mid + dp(4f), controlTop + dp(28f), w - pad, controlTop + dp(68f), surface, outline = false, alpha = alpha)
                paint.style = Paint.Style.FILL; paint.color = ColorUtils.setAlphaComponent(accent, alpha)
                box.set(mid - dp(40f), controlTop + dp(40f), mid - dp(16f), controlTop + dp(56f)); c.drawRoundRect(box, dp(8f), dp(8f), paint)
                box.set(w - pad - dp(36f), controlTop + dp(40f), w - pad - dp(12f), controlTop + dp(56f)); c.drawRoundRect(box, dp(8f), dp(8f), paint)
            }
            val cx = w / 2; val cy = (controlTop + controlBottom) / 2
            when (step) {
                Step.DRAG -> {
                    tiles(); rows(0f)
                    val lift = ease(span(.25f, .7f)); val down = lerp(0f, h - pad - controlBottom, lift)
                    val held = span(.12f, .25f) * (1 - span(.78f, .88f))
                    c.save(); c.translate(0f, down); c.scale(1 + .04f * held, 1 + .04f * held, cx, cy)
                    block(pad, controlTop, w - pad, controlBottom, MobilePalette.background); orb(cx, cy, dp(38f)); c.restore()
                    finger(cx, cy + down, held, span(.02f, .12f) * (1 - span(.88f, .98f)))
                }
                Step.DOUBLE -> {
                    tiles(); rows(0f)
                    val morph = ease(span(.48f, .62f))
                    block(pad, controlTop, w - pad, controlBottom, MobilePalette.background)
                    orb(cx, cy, dp(38f), (255 * (1 - morph)).toInt()); switches((255 * morph).toInt())
                    val tap = span(.2f, .26f) * (1 - span(.26f, .32f)) + span(.36f, .42f) * (1 - span(.42f, .48f))
                    finger(cx, cy, tap, span(.05f, .15f) * (1 - span(.85f, .95f)))
                }
                Step.BADGES -> {
                    tiles(); rows(0f)
                    val small = ease(span(.55f, .7f))
                    val bottom = lerp(controlBottom, controlBottom - dp(24f), small)
                    block(pad, controlTop, w - pad, bottom, MobilePalette.background)
                    orb(cx, (controlTop + bottom) / 2, lerp(dp(38f), dp(28f), small))
                    val bx = w - pad - dp(8f); val by = controlTop + dp(2f)
                    badge(bx - dp(26f), by); badge(bx, by)
                    val press = span(.4f, .48f) * (1 - span(.48f, .55f))
                    finger(bx, by, press, span(.1f, .25f) * (1 - span(.85f, .95f)))
                }
                Step.REMOVE -> {
                    val gone = ease(span(.3f, .42f)) * (1 - ease(span(.72f, .84f)))
                    tiles(removed = gone); rows(0f)
                    block(pad, controlTop, w - pad, controlBottom, MobilePalette.background); orb(cx, cy, dp(38f))
                    val size = (w - 2 * pad - dp(8f) * 4) / 5
                    val bx = pad + 3 * (size + dp(8f)) + size; val by = tilesTop
                    badge(bx, by, minus = gone < .5f)
                    val press = span(.22f, .3f) * (1 - span(.3f, .36f)) + span(.62f, .7f) * (1 - span(.7f, .76f))
                    finger(bx, by, press, span(.08f, .2f) * (1 - span(.86f, .96f)))
                }
                Step.SCROLL -> {
                    val shift = -ease(span(.25f, .7f)) * (controlBottom - pad + dp(8f)) * (1 - ease(span(.82f, .95f)))
                    c.save(); c.clipRect(0f, 0f, w, h)
                    tiles(shift); block(pad, controlTop + shift, w - pad, controlBottom + shift, MobilePalette.background, outline = false); orb(cx, cy + shift, dp(38f))
                    var y = listTop + shift
                    while (y < h - pad) { if (y + dp(30f) > 0) block(pad, y, w - pad, y + dp(30f), surface, outline = false, alpha = 200); y += dp(38f) }
                    c.restore()
                    val fy = lerp(h * .78f, h * .78f - (controlBottom - pad), ease(span(.25f, .7f)))
                    finger(cx, fy, span(.18f, .25f) * (1 - span(.7f, .76f)), span(.08f, .18f) * (1 - span(.76f, .86f)))
                }
            }
            canvas = null
        }
    }
}
