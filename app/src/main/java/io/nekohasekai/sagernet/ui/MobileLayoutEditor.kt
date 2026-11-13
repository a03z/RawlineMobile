package app.rawline.mobile.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.rawline.mobile.Key
import app.rawline.mobile.R
import app.rawline.mobile.database.DataStore
import java.util.Locale

private typealias LayoutAction = Pair<View, () -> Unit>

/**
 * A miniature of the home screen in the current style, its parts trembling with dashed outlines as on a phone's home
 * screen in edit mode: hold a part to move it, tap it twice to change its look, and the small buttons on it change its
 * type, size, merging, or take a tile or switch away (minus) and bring it back (plus). The tutorial shows each gesture.
 */
class MobileLayoutEditorActivity : ServerScreenActivity() {
    private enum class Part(val title: Int) {
        TILES(R.string.qmobile_layout_tiles), CHIP(R.string.qmobile_layout_chip), CONTROL(R.string.qmobile_layout_control)
    }

    private val ui by lazy { ServerUi(this) }
    private fun dp(n: Int) = ui.dp(n)
    private val accent get() = MobilePalette.accent(this)
    private val parts = mutableMapOf<Part, FrameLayout>()
    private val dashes = mutableMapOf<Part, GradientDrawable>()
    /** Every small button in the miniature with what it does, by the part (or the list) it sits on. */
    private val actions = mutableMapOf<Any, List<LayoutAction>>()
    private lateinit var column: LinearLayout
    private lateinit var list: FrameLayout
    private lateinit var root: FrameLayout
    private var dragged: Part? = null
    private var grabY = 0f
    private var fingerY = 0f
    private var blink: ValueAnimator? = null
    private var tutorial: MobileLayoutTutorial? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(MobilePalette.background))
        root = FrameLayout(this)
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(Toolbar(this).apply {
            title = getString(R.string.qmobile_edit_layout)
            setTitleTextColor(MobilePalette.text)
            setNavigationIcon(R.drawable.baseline_arrow_back_24); setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        })
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(20)); clipChildren = false; clipToPadding = false
            background = MobileHomeBackdrop()
        }
        list = serverList()
        val available = if (MobileStyle.new) Part.values().toList() else listOf(Part.TILES, Part.CONTROL)
        available.forEach { parts[it] = frameFor(it); fill(it) }
        arrange()

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val body = LinearLayout(this).apply { setPadding(dp(16), 0, dp(16), dp(4)) }
        body.addView(FrameLayout(this).apply { addView(Miniature(this@MobileLayoutEditorActivity), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)) },
            if (landscape) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, -1))
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        // No instructions on the screen: one link opens the animated tutorial again.
        page.addView(ui.text(getString(R.string.qmobile_layout_tutorial), 15f, accent, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(dp(16), dp(12), dp(16), dp(14)); isFocusable = true
            setOnClickListener { showTutorial() }; MobileFeedback.apply(this)
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { tutorial?.close() }
        }.also { backToEditor = it })
        if (!DataStore.configurationStore.getBoolean(TUTORIAL_SEEN, false)) root.post { showTutorial() }
    }

    /** Back closes the tutorial first, then the editor. */
    private var backToEditor: androidx.activity.OnBackPressedCallback? = null

    private fun showTutorial() {
        if (tutorial != null) return
        backToEditor?.isEnabled = true
        tutorial = MobileLayoutTutorial(this) {
            tutorial = null; backToEditor?.isEnabled = false
            DataStore.configurationStore.putBoolean(TUTORIAL_SEEN, true)
        }.also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
    }

    override fun onStart() {
        super.onStart()
        blink = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 1300; repeatCount = ValueAnimator.INFINITE; interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { animator ->
                val phase = animator.animatedValue as Float
                // The outlines breathe; the parts tremble slightly, each a little out of step.
                val alpha = (157 + 98 * kotlin.math.sin(phase)).toInt()
                dashes.forEach { (part, dash) -> dash.alpha = if (part == dragged) 255 else alpha }
                parts.entries.forEachIndexed { index, (part, frame) ->
                    frame.rotation = if (part == dragged) 0f else .35f * kotlin.math.sin(phase * 4 + index * 1.7f)
                }
            }
            start()
        }
    }

    override fun onStop() {
        blink?.cancel(); blink = null
        parts.values.forEach { it.rotation = 0f }
        super.onStop()
    }

    private fun isBottom(part: Part) = when (part) {
        Part.TILES -> MobileHomeLayout.tilesBottom
        Part.CHIP -> MobileHomeLayout.chipBottom
        Part.CONTROL -> MobileHomeLayout.controlBottom
    }

    private fun place(part: Part, bottom: Boolean) {
        when (part) {
            Part.TILES -> MobileHomeLayout.tilesBottom = bottom
            Part.CHIP -> MobileHomeLayout.chipBottom = bottom
            Part.CONTROL -> MobileHomeLayout.controlBottom = bottom
        }
        // The control looks different at the bottom, in a panel of its own.
        if (part == Part.CONTROL) fill(part)
        rearrange()
    }

    private fun rearrange() {
        TransitionManager.beginDelayedTransition(column, ChangeBounds().setDuration(180).apply { dragged?.let { excludeTarget(parts.getValue(it), true) } })
        arrange()
    }

    /** The home screen's order: from the top edge tiles, then subscription and control in either order; the same ones mirrored under the list. */
    private fun arrange() {
        column.removeAllViews()
        val order = (if (MobileHomeLayout.chipNearList) listOf(Part.TILES, Part.CONTROL, Part.CHIP) else listOf(Part.TILES, Part.CHIP, Part.CONTROL)).filter { it in parts }
        fun add(part: Part) = column.addView(parts.getValue(part), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        order.filterNot(::isBottom).forEach(::add)
        column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
        order.reversed().filter(::isBottom).forEach(::add)
    }

    private fun frameFor(part: Part) = FrameLayout(this).apply {
        setPadding(dp(4), dp(5), dp(4), dp(5)); clipChildren = false; clipToPadding = false
        val dash = GradientDrawable().apply {
            cornerRadius = dp(MobilePalette.rowDp).toFloat()
            setStroke(dp(2), accent, dp(7).toFloat(), dp(5).toFloat())
        }
        dashes[part] = dash
        foreground = dash
        contentDescription = getString(part.title)
        // Touches go through the miniature; a remote, keyboard or screen reader gets the same choices as a menu.
        tag = MobileFeedback.NONE
        isFocusable = true
        setOnClickListener { menu(part, it) }
        setOnFocusChangeListener { view, focused -> view.animate().scaleX(if (focused) 1.02f else 1f).scaleY(if (focused) 1.02f else 1f).setDuration(120).start() }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (dragged == part) follow() }
    }

    private fun menu(part: Part, anchor: View) {
        val popup = PopupMenu(this, anchor)
        val positions = resources.getStringArray(R.array.qmobile_tiles_positions)
        positions.forEachIndexed { index, label ->
            popup.menu.add(label).setOnMenuItemClickListener {
                if (part == Part.CHIP) MobileHomeLayout.chipNearList = false
                place(part, index == 1); anchor.requestFocus(); true
            }
        }
        if (part == Part.CHIP) {
            popup.menu.add(R.string.qmobile_layout_merge).setOnMenuItemClickListener { setChipExtended(!MobileHomeLayout.extendedChip); true }
            popup.menu.add(R.string.qmobile_layout_chip_near).setOnMenuItemClickListener {
                MobileHomeLayout.chipNearList = true
                place(part, MobileHomeLayout.controlBottom); anchor.requestFocus(); true
            }
        }
        if (part == Part.CONTROL) popup.menu.add(R.string.qmobile_layout_swap).setOnMenuItemClickListener { setSwitches(!MobileHomeLayout.controlSwitches); true }
        if (part == Part.TILES) popup.menu.add(R.string.qmobile_layout_swap).setOnMenuItemClickListener { setExtended(!MobileHomeLayout.extendedTiles); true }
        if (part != Part.CHIP) popup.menu.add(R.string.qmobile_layout_size).setOnMenuItemClickListener { toggleSize(part); true }
        popup.menu.add(R.string.qmobile_layout_scroll).setOnMenuItemClickListener { toggleScroll(); true }
        popup.show()
    }

    private fun setSwitches(switches: Boolean) {
        MobileHomeLayout.controlSwitches = switches
        fill(Part.CONTROL)
    }

    private fun setChipExtended(extended: Boolean) {
        MobileHomeLayout.extendedChip = extended
        fill(Part.CHIP)
    }

    private fun setExtended(extended: Boolean) {
        MobileHomeLayout.extendedTiles = extended
        fill(Part.TILES)
    }

    private fun toggleSize(part: Part) {
        if (part == Part.TILES) MobileHomeLayout.compactTiles = !MobileHomeLayout.compactTiles
        else MobileHomeLayout.compactControl = !MobileHomeLayout.compactControl
        TransitionManager.beginDelayedTransition(column, ChangeBounds().setDuration(180))
        fill(part)
    }

    private fun toggleScroll() {
        MobileHomeLayout.scrollTogether = !MobileHomeLayout.scrollTogether
        decorateList()
        // The parts above the list rise with it for a moment, to show what scrolls together.
        if (MobileHomeLayout.scrollTogether) parts.filterKeys { !isBottom(it) }.values.forEach { frame ->
            frame.animate().translationY(-dp(14).toFloat()).setDuration(220).withEndAction { frame.animate().translationY(0f).setDuration(220).start() }.start()
        }
    }

    private fun fill(part: Part) {
        val box = parts.getValue(part)
        box.removeAllViews()
        val items = mutableListOf<LayoutAction>()
        box.addView(when (part) {
            Part.TILES -> tiles(items)
            Part.CHIP -> chip()
            Part.CONTROL -> control(items)
        }, FrameLayout.LayoutParams(-1, -2))
        val corner = LinearLayout(this).apply { gravity = Gravity.END; translationY = -dp(14).toFloat(); translationX = dp(8).toFloat() }
        fun button(icon: Int, description: Int, action: () -> Unit) {
            val view = badge(icon, accent, Color.WHITE, description)
            corner.addView(view, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(6) })
            items.add(view to action)
        }
        when (part) {
            Part.TILES -> {
                button(R.drawable.ic_layout_swap, R.string.qmobile_layout_swap) { setExtended(!MobileHomeLayout.extendedTiles) }
                button(if (MobileHomeLayout.compactTiles) R.drawable.ic_layout_grow else R.drawable.ic_layout_shrink, R.string.qmobile_layout_size) { toggleSize(part) }
            }
            Part.CHIP -> button(if (MobileHomeLayout.extendedChip) R.drawable.ic_layout_split else R.drawable.ic_layout_merge, R.string.qmobile_layout_merge) { setChipExtended(!MobileHomeLayout.extendedChip) }
            Part.CONTROL -> {
                button(R.drawable.ic_layout_swap, R.string.qmobile_layout_swap) { setSwitches(!MobileHomeLayout.controlSwitches) }
                button(if (MobileHomeLayout.compactControl) R.drawable.ic_layout_grow else R.drawable.ic_layout_shrink, R.string.qmobile_layout_size) { toggleSize(part) }
            }
        }
        box.addView(corner, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        actions[part] = items
    }

    /** A small round button of the miniature: an icon on a disc; its action runs from [Miniature]'s tap. */
    private fun badge(icon: Int, fill: Int, tint: Int, description: Int) = ImageView(this).apply {
        setImageResource(icon); imageTintList = ColorStateList.valueOf(tint); setPadding(dp(5), dp(5), dp(5), dp(5))
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill); setStroke(dp(1), ColorUtils.setAlphaComponent(Color.BLACK, 40)) }
        elevation = dp(2).toFloat(); contentDescription = getString(description)
    }

    /** [view] with a minus (or, once taken away, a plus) in its corner. */
    private fun removable(view: View, removed: Boolean, items: MutableList<LayoutAction>, action: () -> Unit) = FrameLayout(this).apply {
        clipChildren = false; clipToPadding = false
        if (removed) {
            view.alpha = .35f
            foreground = GradientDrawable().apply { cornerRadius = dp(MobilePalette.rowDp).toFloat(); setStroke(dp(2), MobilePalette.muted, dp(5).toFloat(), dp(4).toFloat()) }
        }
        addView(view, FrameLayout.LayoutParams(-1, -1))
        val corner = badge(if (removed) R.drawable.ic_mobile_add else R.drawable.ic_layout_remove, if (removed) accent else Color.rgb(220, 66, 61), Color.WHITE,
            if (removed) R.string.qmobile_layout_restore else R.string.qmobile_layout_remove).apply { translationX = dp(8).toFloat(); translationY = -dp(8).toFloat() }
        addView(corner, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END))
        items.add(corner to action)
    }

    private fun icon(res: Int, tint: Int = MobilePalette.text) = ImageView(this).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(tint); background = MobilePalette.rowShape(context)
        val pad = dp(if (MobileHomeLayout.compactTiles) 10 else 12); setPadding(pad, pad, pad, pad)
    }

    private fun tiles(items: MutableList<LayoutAction>) = LinearLayout(this).apply {
        gravity = Gravity.CENTER; clipChildren = false; clipToPadding = false
        val new = MobileStyle.new
        val size = dp(MobileHomeLayout.tileSize())
        if (MobileHomeLayout.extendedTiles) {
            val hidden = MobileHomeLayout.hiddenTiles
            val quick = listOf("mode" to R.drawable.ic_mobile_tun, "route" to R.drawable.ic_mobile_route, "groups" to R.drawable.ic_mobile_subscriptions, "updates" to R.drawable.ic_mobile_update)
            val views = listOf<View>(icon(R.drawable.ic_mobile_settings)) + quick.map { (id, res) ->
                val removed = id in hidden
                removable(icon(res), removed, items) {
                    MobileHomeLayout.hiddenTiles = if (removed) hidden - id else hidden + id
                    TransitionManager.beginDelayedTransition(column, ChangeBounds().setDuration(160)); fill(Part.TILES)
                }
            } + if (new) emptyList() else listOf(icon(R.drawable.ic_mobile_add))
            views.forEachIndexed { index, view -> addView(view, LinearLayout.LayoutParams(size, size).apply { if (index < views.lastIndex) marginEnd = dp(8) }) }
        } else {
            addView(icon(if (new) R.drawable.ic_mobile_more else R.drawable.ic_mobile_settings), LinearLayout.LayoutParams(size, size))
            addView(ui.text(getString(R.string.app_name), 18f, bold = true).apply { gravity = Gravity.CENTER; maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(icon(if (new) R.drawable.ic_mobile_settings else R.drawable.ic_mobile_add), LinearLayout.LayoutParams(size, size))
        }
    }

    private fun chip() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        if (MobileHomeLayout.extendedChip) addView(ui.text(getString(R.string.qmobile_route_chip), 16f, bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL; maxLines = 1; setPadding(dp(16), 0, dp(12), 0); background = MobilePalette.rowShape(context)
        }, LinearLayout.LayoutParams(0, dp(48), .8f).apply { marginEnd = dp(8) })
        addView(ui.text(getString(R.string.subscription), 16f, bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL; maxLines = 1; setPadding(dp(16), 0, dp(12), 0); background = MobilePalette.rowShape(context)
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        addView(icon(R.drawable.ic_mobile_add), LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    private fun check() = ui.text(getString(R.string.qmobile_check_connection), 12f, MobilePalette.muted).apply { gravity = Gravity.CENTER_VERTICAL }

    private fun control(items: MutableList<LayoutAction>): View {
        val new = MobileStyle.new
        val bottom = MobileHomeLayout.controlBottom
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        fun status() = box.addView(check().apply { setPadding(dp(10), 0, dp(6), 0) }, LinearLayout.LayoutParams(-1, dp(40)))
        if (MobileHomeLayout.controlSwitches) {
            if (new && bottom) status()
            val hidden = MobileHomeLayout.hiddenSwitch
            val height = dp(if (MobileHomeLayout.compactControl) 44 else 52)
            box.addView(LinearLayout(this).apply {
                isBaselineAligned = false; clipChildren = false; clipToPadding = false
                listOf(Key.MODE_VPN to R.string.service_mode_vpn, Key.MODE_PROXY to R.string.service_mode_proxy).forEachIndexed { index, (mode, label) ->
                    val cell: View = if (mode == hidden) {
                        // What the home screen puts in a removed switch's place: the server in use.
                        ui.text("[" + getString(R.string.subscription) + "] " + Locale("", "de").displayCountry, 13f, MobilePalette.muted).apply {
                            gravity = Gravity.START or Gravity.CENTER_VERTICAL; maxLines = 2; setPadding(dp(14), dp(2), dp(8), dp(2)); background = MobilePalette.rowShape(context)
                        }
                    } else LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(2), dp(8), dp(2)); background = MobilePalette.rowShape(context)
                        addView(ui.text(getString(label), 14f), LinearLayout.LayoutParams(0, -2, 1f))
                        addView(SwitchCompat(context).apply { isClickable = false; isFocusable = false })
                    }
                    // One switch always stays: only the other can be taken away.
                    val wrapped = if (hidden.isEmpty() || mode == hidden) removable(cell, mode == hidden, items) {
                        MobileHomeLayout.hiddenSwitch = if (mode == hidden) "" else mode
                        TransitionManager.beginDelayedTransition(column, ChangeBounds().setDuration(160)); fill(Part.CONTROL)
                    } else cell
                    addView(wrapped, LinearLayout.LayoutParams(0, height, 1f).apply { if (index > 0) marginStart = dp(8) })
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            if (new && !bottom) status()
            return box
        }
        val size = dp(MobileHomeLayout.orbSize(new))
        val orb = View(this).apply {
            background = if (new) MobileOrbArtwork(context, getString(R.string.qmobile_orb_idle))
                else MobilePowerArtwork(accent).apply { statusText = getString(R.string.qmobile_connect) }
        }
        val centre = FrameLayout(this)
        centre.addView(orb, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        if (new && bottom) {
            centre.background = MobileDockGlass(MobilePalette.cardDp * resources.displayMetrics.density, MobileOrbArtwork.centerY(size), MobileOrbArtwork.radius(size) * 1.26f, dp(104).toFloat() * size / dp(190))
            centre.addView(check().apply { maxLines = 3 }, FrameLayout.LayoutParams(dp(92), -2, Gravity.START or Gravity.BOTTOM).apply { marginStart = dp(14); bottomMargin = dp(14) })
        }
        box.addView(centre, LinearLayout.LayoutParams(-1, if (new) size + dp(4) else size + dp(18)))
        if (new && !bottom) status()
        return box
    }

    /** Stays put and only frames the rest; it takes whatever height the movable parts leave. */
    private fun serverList() = FrameLayout(this).apply {
        clipChildren = false; clipToPadding = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; alpha = .55f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            if (!MobileStyle.new) addView(check(), LinearLayout.LayoutParams(-1, dp(32)))
            for (code in listOf("de", "fi", "nl", "se", "us", "jp", "gb", "ca", "fr", "pl")) addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0); background = MobilePalette.rowShape(context)
                addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_XY; clipToOutline = true
                    background = GradientDrawable().apply { cornerRadius = dp(7).toFloat() }
                    MobileCountryFlags.bitmap(context, code)?.let(::setImageBitmap)
                }, LinearLayout.LayoutParams(dp(30), dp(22)).apply { marginEnd = dp(12) })
                addView(ui.text(Locale("", code).displayCountry, 15f).apply { maxLines = 1 })
            }, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(6) })
        }, FrameLayout.LayoutParams(-1, -2))
        post { decorateList() }
    }

    /** The list's own button: lit while the parts above it scroll with it, plain while they stay on the screen. */
    private fun decorateList() {
        list.findViewWithTag<View>(LIST_BADGE)?.let(list::removeView)
        val together = MobileHomeLayout.scrollTogether
        val button = badge(R.drawable.ic_layout_scroll, if (together) accent else MobilePalette.background, if (together) Color.WHITE else accent, R.string.qmobile_layout_scroll).apply { tag = LIST_BADGE }
        list.addView(button, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.END).apply { topMargin = dp(10); marginEnd = dp(8) })
        actions[LIST_BADGE] = listOf(button to { toggleScroll() })
    }

    private fun partAt(y: Float) = parts.entries.firstOrNull { y >= it.value.top && y < it.value.bottom }?.key

    /** The small button under a tap in [column]'s coordinates, with a margin around it for a finger. */
    private fun actionAt(x: Float, y: Float): (() -> Unit)? {
        val rect = Rect()
        val margin = dp(10)
        return actions.values.flatten().firstOrNull { (view, _) ->
            if (!view.isShown) return@firstOrNull false
            view.getDrawingRect(rect)
            column.offsetDescendantRectToMyCoords(view, rect)
            rect.offset(view.translationX.toInt(), view.translationY.toInt())
            rect.inset(-margin, -margin)
            rect.contains(x.toInt(), y.toInt())
        }?.second
    }

    private fun startDrag(part: Part, y: Float) {
        val frame = parts.getValue(part)
        dragged = part
        fingerY = y
        grabY = y - frame.top
        frame.rotation = 0f
        frame.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        frame.animate().scaleX(1.03f).scaleY(1.03f).translationZ(dp(8).toFloat()).setDuration(120).start()
    }

    private fun drag(y: Float) {
        val part = dragged ?: return
        fingerY = y
        val bottom = y > list.top + list.height / 2f
        if (bottom != isBottom(part)) place(part, bottom)
        // On the control's side the finger also chooses which side of the control the subscription takes.
        if (part == Part.CHIP && bottom == MobileHomeLayout.controlBottom) {
            val control = parts.getValue(Part.CONTROL)
            val middle = control.top + control.height / 2f
            val near = if (bottom) y < middle else y > middle
            if (near != MobileHomeLayout.chipNearList) { MobileHomeLayout.chipNearList = near; rearrange() }
        }
        // And the control, dragged past the subscription on its side, goes between it and the edge or the list.
        if (part == Part.CONTROL && MobileStyle.new && Part.CHIP in parts && bottom == MobileHomeLayout.chipBottom) {
            val chip = parts.getValue(Part.CHIP)
            val middle = chip.top + chip.height / 2f
            val near = if (bottom) y > middle else y < middle
            if (near != MobileHomeLayout.chipNearList) { MobileHomeLayout.chipNearList = near; rearrange() }
        }
        follow()
    }

    private fun follow() {
        val frame = dragged?.let(parts::getValue) ?: return
        frame.translationY = (fingerY - grabY).coerceIn(0f, (column.height - frame.height).coerceAtLeast(0).toFloat()) - frame.top
    }

    private fun drop() {
        val frame = dragged?.let(parts::getValue) ?: return
        dragged = null
        frame.animate().translationY(0f).scaleX(1f).scaleY(1f).translationZ(0f).setDuration(160).start()
    }

    /** The whole screen at the phone's own proportions, scaled to the room there is; every touch lands here. */
    private inner class Miniature(context: Context) : ViewGroup(context) {
        private var scale = 1f
        private val virtualWidth = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        private val virtualHeight = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                val action = actionAt(event.x / scale, event.y / scale) ?: return false
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                action()
                return true
            }
            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (actionAt(event.x / scale, event.y / scale) != null) return true
                when (partAt(event.y / scale)) {
                    Part.TILES -> setExtended(!MobileHomeLayout.extendedTiles)
                    Part.CONTROL -> setSwitches(!MobileHomeLayout.controlSwitches)
                    Part.CHIP -> setChipExtended(!MobileHomeLayout.extendedChip)
                    else -> {}
                }
                return true
            }
            override fun onLongPress(event: MotionEvent) { partAt(event.y / scale)?.let { startDrag(it, event.y / scale) } }
        })

        init {
            addView(column)
            background = GradientDrawable().apply { setColor(MobilePalette.background); cornerRadius = dp(MobilePalette.cardDp).toFloat() }
            foreground = GradientDrawable().apply { cornerRadius = dp(MobilePalette.cardDp).toFloat(); setStroke(dp(1).coerceAtLeast(1), MobilePalette.outline) }
            clipToOutline = true
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            scale = minOf(1f, MeasureSpec.getSize(widthSpec).toFloat() / virtualWidth, MeasureSpec.getSize(heightSpec).toFloat() / virtualHeight)
            column.measure(MeasureSpec.makeMeasureSpec(virtualWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(virtualHeight, MeasureSpec.EXACTLY))
            setMeasuredDimension((virtualWidth * scale).toInt(), (virtualHeight * scale).toInt())
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            column.layout(0, 0, virtualWidth, virtualHeight)
            column.pivotX = 0f; column.pivotY = 0f
            column.scaleX = scale; column.scaleY = scale
        }

        override fun onInterceptTouchEvent(event: MotionEvent) = true

        override fun onTouchEvent(event: MotionEvent): Boolean {
            gestures.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> drag(event.y / scale)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drop()
            }
            return true
        }
    }

    private companion object {
        const val TUTORIAL_SEEN = "qmobileLayoutTutorialSeen"
        const val LIST_BADGE = "listBadge"
    }
}
