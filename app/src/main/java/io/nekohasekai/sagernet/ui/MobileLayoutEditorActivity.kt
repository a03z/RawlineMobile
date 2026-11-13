package app.rawline.mobile.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
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
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.rawline.mobile.R
import app.rawline.mobile.database.DataStore
import java.util.Locale

/** Where the home screen's parts stand: the same answers for the home screen and its editor. */
object MobileHomeLayout {
    private val store get() = DataStore.configurationStore
    private fun position(bottom: Boolean) = if (bottom) "bottom" else "top"

    var tilesBottom: Boolean
        get() = if (MobileStyle.new) store.getString("qmobileNewTilesPosition") == "bottom"
            else (store.getString("qmobileTilesPosition") ?: position(store.getBoolean("qmobileTilesBottom", false))) == "bottom"
        set(value) = store.putString(if (MobileStyle.new) "qmobileNewTilesPosition" else "qmobileTilesPosition", position(value))
    var extendedTiles: Boolean
        get() = if (MobileStyle.new) store.getString("qmobileNewTiles") == "on" else store.getString("qmobileHomeTilesMode") != "standard"
        set(value) = if (MobileStyle.new) store.putString("qmobileNewTiles", if (value) "on" else "off")
            else store.putString("qmobileHomeTilesMode", if (value) "extended" else "standard")
    var controlBottom: Boolean
        get() = store.getString("qmobileControlPosition") == "bottom"
        set(value) = store.putString("qmobileControlPosition", position(value))
    var controlSwitches: Boolean
        get() = store.getString("qmobileHomeControl") == "switches"
        set(value) = store.putString("qmobileHomeControl", if (value) "switches" else "button")
    /** The classic style has no subscription row. */
    var chipBottom: Boolean
        get() = MobileStyle.new && store.getString("qmobileChipPosition") == "bottom"
        set(value) = store.putString("qmobileChipPosition", position(value))
    /** The routing profile beside the subscription, in the new style. */
    var extendedChip: Boolean
        get() = MobileStyle.new && store.getBoolean("qmobileChipExtended", false)
        set(value) = store.putBoolean("qmobileChipExtended", value)
    /** Between the connection control and the servers, rather than on the control's outer side. */
    var chipNearList: Boolean
        get() = MobileStyle.new && store.getBoolean("qmobileChipNearList", false)
        set(value) = store.putBoolean("qmobileChipNearList", value)
    /** The parts ("tiles", "chip", "control") above the server list that scroll away with it (portrait); the rest stay on the screen. */
    var scrollParts: Set<String>
        get() = store.getString("qmobileScrollParts").orEmpty().split(',').filter { it.isNotEmpty() }.toSet()
        set(value) = store.putString("qmobileScrollParts", value.sorted().joinToString(","))
    /** A smaller connect button, or lower switches. */
    var compactControl: Boolean
        get() = store.getBoolean("qmobileCompactControl", false)
        set(value) = store.putBoolean("qmobileCompactControl", value)
    var compactTiles: Boolean
        get() = store.getBoolean("qmobileCompactTiles", false)
        set(value) = store.putBoolean("qmobileCompactTiles", value)
    /** A mode switch taken off the screen ("" none, or Key.MODE_VPN / Key.MODE_PROXY); its place shows the server in use. */
    var hiddenSwitch: String
        get() = store.getString("qmobileHiddenSwitch").orEmpty()
        set(value) = store.putString("qmobileHiddenSwitch", value)
    /** The quick tiles of the tile bar, by id; the settings tile always stays. */
    val QUICK_TILES = listOf("mode", "route", "groups", "updates")
    var hiddenTiles: Set<String>
        get() = store.getString("qmobileHiddenTiles").orEmpty().split(',').filter { it in QUICK_TILES }.toSet()
        set(value) = store.putString("qmobileHiddenTiles", QUICK_TILES.filter { it in value }.joinToString(","))
    fun tileSize() = if (compactTiles) 40 else 48
    fun orbSize(new: Boolean) = if (compactControl) 148 else if (new) 190 else 188
}
