package app.rawline.mobile.ui

import android.content.Context
import android.graphics.Color
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.utils.Theme
import com.google.android.material.color.MaterialColors

object MobileStyle {
    /** Pastel keeps the New screen structure and uses its own paint. */
    val pastel get() = DataStore.uiStyle == "pastel"
    /** The New look alone. Shared structure belongs to [modern], so a New-only check stays off Pastel. */
    val new get() = DataStore.uiStyle == "new"
    /** New and Pastel: one screen structure, different paint. */
    val modern get() = new || pastel
}

object MobilePalette {
    /** Pastel night stays plum. True black is only the OLED choice. New still treats every night as OLED. */
    val oled get() = Theme.usingNightMode() && (if (MobileStyle.pastel) DataStore.amoledTheme else DataStore.amoledTheme || MobileStyle.new)
    val light get() = !oled && !Theme.usingNightMode()
    private val lilac = Color.rgb(0x7C, 0x6B, 0xF2)
    val background get() = when {
        MobileStyle.pastel && oled -> Color.BLACK
        MobileStyle.pastel && light -> Color.rgb(255, 228, 214)
        MobileStyle.pastel -> Color.rgb(22, 20, 31)
        oled -> Color.BLACK
        light -> if (MobileStyle.modern) Color.WHITE else Color.rgb(236, 237, 239)
        else -> Color.rgb(30, 30, 30)
    }
    /** Translucent in the new style: anything that has to hide what is under it uses [solid] or [frosted]. */
    val surface get() = when {
        MobileStyle.pastel && light -> Color.WHITE
        MobileStyle.pastel && oled -> Color.BLACK
        MobileStyle.pastel -> Color.rgb(42, 36, 62)
        MobileStyle.modern -> if (light) Color.argb(190, 255, 255, 255) else Color.argb(18, 255, 255, 255)
        else -> solid
    }
    val solid get() = when {
        MobileStyle.pastel && light -> Color.rgb(255, 247, 252)
        MobileStyle.pastel && oled -> Color.rgb(16, 14, 22)
        MobileStyle.pastel -> Color.rgb(36, 31, 51)
        MobileStyle.modern -> if (light) Color.rgb(243, 243, 246) else Color.rgb(21, 21, 24)
        oled -> Color.rgb(15, 15, 15)
        light -> Color.rgb(248, 249, 250)
        else -> Color.rgb(49, 51, 54)
    }
    val toolbar get() = if (MobileStyle.modern) Color.TRANSPARENT else if (oled) Color.BLACK else if (light) (if (MobileStyle.modern) Color.WHITE else Color.rgb(231,233,235)) else Color.rgb(34,35,37)
    val text get() = when {
        MobileStyle.pastel && light -> Color.rgb(0x1B, 0x1C, 0x2A)
        MobileStyle.pastel -> Color.rgb(0xF4, 0xF2, 0xFA)
        MobileStyle.modern -> if (light) Color.rgb(10, 10, 11) else Color.rgb(245, 245, 247)
        light -> Color.rgb(39, 44, 48)
        else -> Color.rgb(231, 234, 237)
    }
    val muted get() = when {
        MobileStyle.pastel && light -> Color.rgb(110, 108, 136)
        MobileStyle.pastel -> Color.rgb(176, 170, 198)
        MobileStyle.modern -> if (light) Color.rgb(105, 105, 112) else Color.rgb(142, 142, 150)
        light -> Color.rgb(100, 110, 119)
        else -> Color.rgb(173, 182, 191)
    }
    /** The dark field inside a card; on true black a lighter one, or it would vanish. */
    val well get() = when {
        MobileStyle.pastel && light -> Color.argb(18, 124, 107, 242)
        MobileStyle.pastel -> Color.argb(28, 255, 255, 255)
        MobileStyle.modern -> if (light) Color.argb(16, 0, 0, 0) else Color.argb(20, 255, 255, 255)
        else -> androidx.core.graphics.ColorUtils.blendARGB(surface, Color.BLACK, .14f)
    }
    /** Corner radii in dp: the new style is rounder throughout. */
    val rowDp get() = if (MobileStyle.modern) 20 else 5
    val cardDp get() = if (MobileStyle.modern) 28 else 12
    val wellDp get() = if (MobileStyle.modern) 18 else 12
    /** A screen's own colour: the new style's screens let the backdrop through. */
    val screen get() = if (MobileStyle.modern) Color.TRANSPARENT else background
    /** Over content that must stay readable: notices, dialogs, sheets. */
    /** The blur behind sheets and dialogs: enough to lift them off the screen, while what is behind stays recognisable. */
    const val BLUR_DP = 20
    val windowDim get() = if (light) .35f else .6f
    val frosted get() = when {
        MobileStyle.pastel && light -> Color.argb(248, 255, 250, 252)
        MobileStyle.pastel -> Color.argb(248, 24, 20, 36)
        light -> Color.argb(248, 250, 250, 252)
        else -> Color.argb(248, 0, 0, 0)
    }
    val outline get() = androidx.core.graphics.ColorUtils.setAlphaComponent(muted,55)
    fun accent(context: Context) = when {
        MobileStyle.pastel -> lilac
        MobileStyle.modern -> text
        !DataStore.useSystemTheme && DataStore.appTheme == Theme.CYAN -> Color.rgb(87, 190, 229)
        else -> MaterialColors.getColor(context, androidx.appcompat.R.attr.colorAccent, Color.rgb(87, 190, 229))
    }

    /** A row or button: every list row and action in the app. */
    fun rowShape(context: Context): android.graphics.drawable.Drawable = shape(context, rowDp)
    /** A card that holds rows, fields or text: also the dialogs, notices and sheets. */
    fun cardShape(context: Context): android.graphics.drawable.Drawable = shape(context, cardDp)
    /** The chosen row or the main action: a tint and an outline of [color], never a solid fill, the same on every theme. */
    fun selectedShape(context: Context, color: Int = accent(context)): android.graphics.drawable.Drawable {
        val density = context.resources.displayMetrics.density
        if (MobileStyle.pastel) return android.graphics.drawable.GradientDrawable().apply {
            setColor(if (light) Color.rgb(0xF0, 0xEB, 0xFF) else Color.argb(56, 0x7C, 0x6B, 0xF2))
            cornerRadius = rowDp * density
        }
        if (MobileStyle.modern) return MobileGlass(rowDp * density, color)
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(androidx.core.graphics.ColorUtils.blendARGB(surface, color, .16f))
            cornerRadius = rowDp * density; setStroke(density.toInt().coerceAtLeast(1), color)
        }
    }
    /** A button revealed behind a swiped row: a drop of glass in [color], clear of its neighbours. */
    fun actionShape(context: Context, color: Int): android.graphics.drawable.Drawable {
        val density = context.resources.displayMetrics.density
        return android.graphics.drawable.InsetDrawable(MobileGlass(rowDp * density, color), (4 * density).toInt())
    }
    /** A notice over the content behind it. */
    fun frostedShape(context: Context): android.graphics.drawable.Drawable = if (!MobileStyle.modern) shape(context, cardDp) else android.graphics.drawable.GradientDrawable().apply {
        setColor(frosted); cornerRadius = cardDp * context.resources.displayMetrics.density; setStroke(1, outline)
    }
    private fun shape(context: Context, radius: Int): android.graphics.drawable.Drawable {
        val density = context.resources.displayMetrics.density
        if (MobileStyle.pastel) return android.graphics.drawable.GradientDrawable().apply {
            setColor(when {
                light -> Color.WHITE
                oled -> Color.rgb(16, 14, 22)
                else -> Color.rgb(42, 36, 62)
            })
            cornerRadius = radius * density
        }
        if (MobileStyle.modern) return MobileGlass(radius * density)
        return android.graphics.drawable.GradientDrawable().apply { setColor(surface); cornerRadius = radius * density; setStroke(1, outline) }
    }

    /** The caret and selection handles in the accent: the theme draws them black, which vanishes on the dark themes. */
    fun tintCursor(edit: android.widget.EditText) {
        val accent = accent(edit.context)
        edit.highlightColor = androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 80)
        if (android.os.Build.VERSION.SDK_INT < 29) return
        // The theme has no caret drawable to recolour, so the caret is drawn here: 2dp wide, the line's height.
        edit.textCursorDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(accent); setSize((2 * edit.resources.displayMetrics.density).toInt(), 0)
        }
        // A handle the view has not loaded yet comes from the platform theme.
        fun handle(current: android.graphics.drawable.Drawable?, attr: Int) = (current ?: run {
            val styled = edit.context.obtainStyledAttributes(intArrayOf(attr))
            val res = styled.getResourceId(0, 0)
            styled.recycle()
            if (res == 0) null else androidx.core.content.ContextCompat.getDrawable(edit.context, res)
        })?.mutate()?.apply { setTint(accent) }
        handle(edit.textSelectHandle, android.R.attr.textSelectHandle)?.let { edit.setTextSelectHandle(it) }
        handle(edit.textSelectHandleLeft, android.R.attr.textSelectHandleLeft)?.let { edit.setTextSelectHandleLeft(it) }
        handle(edit.textSelectHandleRight, android.R.attr.textSelectHandleRight)?.let { edit.setTextSelectHandleRight(it) }
    }
}
