package com.example.launcherapp.ui.menu

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.isVisible
import com.example.launcherapp.R

/**
 * iOS-style long-press menu for a home-screen icon (Figma: Remove App, Edit Home Screen,
 * Hide App, Select, App info).
 *
 * Shown inside [layer] (a full-screen FrameLayout above the home screen): a dim scrim, a "lifted"
 * copy of the icon, and the menu card right below the icon (or above it when there's no room).
 *
 * "Remove App" doesn't open a dialog: the card is swapped, in the same spot, for the remove card
 * (dialog_remove_app.xml: Delete App / Remove from Home Screen + a separate Cancel card).
 * Tapping the scrim, Cancel or Back closes everything. The activity decides what each action
 * does via [Listener].
 */
class AppContextMenu(
    private val layer: FrameLayout,
    private val listener: Listener
) {

    interface Listener {
        fun onDeleteApp(key: String)
        fun onRemoveFromHome(key: String)
        fun onEditHomeScreen()
        fun onHideApp(key: String)
        fun onSelectApps(key: String)
        fun onAppInfo(key: String)
    }

    var isShowing = false
        private set

    // Where the current icon is (layer coordinates) - the remove card is placed the same way.
    private var iconLeft = 0f
    private var iconTop = 0f
    private var iconWidth = 0
    private var iconHeight = 0
    private var currentCard: View? = null

    /**
     * @param anchorIcon the icon's ImageView on screen (used for position and size)
     * @param showAppInfo false for things that aren't a real app (e.g. the Google folder)
     * @param canDelete false for pre-installed apps / the Google folder (only "Remove from Home Screen")
     */
    fun show(
        key: String,
        anchorIcon: View,
        icon: Drawable?,
        showAppInfo: Boolean = true,
        canDelete: Boolean = true
    ) {
        // A previous fade-out must not finish (and clear the layer) after this new menu appears.
        layer.animate().cancel()
        layer.alpha = 1f
        layer.removeAllViews()
        layer.isVisible = true
        isShowing = true

        val context = layer.context
        val layerLocation = IntArray(2).also(layer::getLocationOnScreen)
        val iconLocation = IntArray(2).also(anchorIcon::getLocationOnScreen)
        iconLeft = (iconLocation[0] - layerLocation[0]).toFloat()
        iconTop = (iconLocation[1] - layerLocation[1]).toFloat()
        iconWidth = anchorIcon.width
        iconHeight = anchorIcon.height

        // 1) Dim scrim - tap to close.
        val scrim = View(context).apply {
            setBackgroundColor(SCRIM_COLOR)
            alpha = 0f
            setOnClickListener { dismiss() }
        }
        layer.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        scrim.animate().alpha(1f).setDuration(ANIM_MS).start()

        // 2) The icon "lifted" above the scrim.
        icon?.let { drawable ->
            val copy = ImageView(context).apply {
                setImageDrawable(drawable.constantState?.newDrawable()?.mutate() ?: drawable)
            }
            layer.addView(copy, FrameLayout.LayoutParams(iconWidth, iconHeight))
            copy.x = iconLeft
            copy.y = iconTop
            copy.animate().scaleX(LIFT_SCALE).scaleY(LIFT_SCALE).setDuration(ANIM_MS).start()
        }

        // 3) The main menu card.
        val menu = LayoutInflater.from(context).inflate(R.layout.view_app_context_menu, layer, false)
        menu.findViewById<View>(R.id.menuAppInfo).isVisible = showAppInfo
        menu.findViewById<View>(R.id.menuAppInfoDivider).isVisible = showAppInfo
        menu.findViewById<View>(R.id.menuRemoveApp).setOnClickListener { showRemoveCard(key, canDelete) }
        menu.findViewById<View>(R.id.menuEditHome).setOnClickListener { pick { listener.onEditHomeScreen() } }
        menu.findViewById<View>(R.id.menuHideApp).setOnClickListener { pick { listener.onHideApp(key) } }
        menu.findViewById<View>(R.id.menuSelect).setOnClickListener { pick { listener.onSelectApps(key) } }
        menu.findViewById<View>(R.id.menuAppInfo).setOnClickListener { pick { listener.onAppInfo(key) } }
        showCard(menu)
    }

    /** Swaps the menu card for the "Remove App" card, in the same spot next to the icon. */
    private fun showRemoveCard(key: String, canDelete: Boolean) {
        val card = LayoutInflater.from(layer.context).inflate(R.layout.dialog_remove_app, layer, false)
        val deleteRow = card.findViewById<View>(R.id.removeAppDelete)
        if (canDelete) {
            deleteRow.setOnClickListener { pick { listener.onDeleteApp(key) } }
        } else {
            deleteRow.isVisible = false
            card.findViewById<View>(R.id.removeAppDeleteDivider).isVisible = false
        }
        card.findViewById<View>(R.id.removeAppFromHome).setOnClickListener { pick { listener.onRemoveFromHome(key) } }
        card.findViewById<View>(R.id.removeAppCancel).setOnClickListener { dismiss() }

        currentCard?.let { old ->
            old.animate().cancel()
            old.animate().alpha(0f).scaleX(SWAP_OUT_SCALE).scaleY(SWAP_OUT_SCALE).setDuration(SWAP_MS)
                .withEndAction { layer.removeView(old) }
                .start()
        }
        showCard(card)
    }

    /** Adds [card] under the icon (or above it if there's no room) and grows it out of the icon. */
    private fun showCard(card: View) {
        layer.addView(card)
        currentCard = card

        val density = layer.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val cardWidth = card.layoutParams.width.takeIf { it > 0 } ?: dp(DEFAULT_WIDTH_DP)
        card.measure(
            View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val cardHeight = card.measuredHeight
        val margin = dp(SCREEN_MARGIN_DP).toFloat()
        val gap = dp(ICON_GAP_DP).toFloat()

        val maxX = (layer.width - cardWidth - margin).coerceAtLeast(margin)
        val x = iconLeft.coerceIn(margin, maxX)
        val belowY = iconTop + iconHeight * LIFT_SCALE + gap
        val fitsBelow = belowY + cardHeight <= layer.height - margin
        val y = if (fitsBelow) belowY else (iconTop - gap - cardHeight).coerceAtLeast(margin)

        card.x = x
        card.y = y
        card.pivotX = (iconLeft + iconWidth / 2f - x).coerceIn(0f, cardWidth.toFloat())
        card.pivotY = if (fitsBelow) 0f else cardHeight.toFloat()
        card.scaleX = START_SCALE
        card.scaleY = START_SCALE
        card.alpha = 0f
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(ANIM_MS).start()
    }

    /** Closes the menu. [animate] = false when a drag takes over (the icon must appear at once). */
    fun dismiss(animate: Boolean = true) {
        if (!isShowing) return
        isShowing = false
        layer.animate().cancel()
        if (!animate) {
            clearLayer()
            return
        }
        layer.animate().alpha(0f).setDuration(DISMISS_MS).withEndAction { clearLayer() }.start()
    }

    private fun clearLayer() {
        layer.removeAllViews()
        layer.isVisible = false
        layer.alpha = 1f
        currentCard = null
    }

    private inline fun pick(action: () -> Unit) {
        dismiss()
        action()
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val SCRIM_COLOR = 0x59000000
        const val LIFT_SCALE = 1.08f
        const val START_SCALE = 0.85f
        const val SWAP_OUT_SCALE = 0.92f
        const val ANIM_MS = 180L
        const val SWAP_MS = 120L
        const val DISMISS_MS = 120L
        const val DEFAULT_WIDTH_DP = 250
        const val SCREEN_MARGIN_DP = 16
        const val ICON_GAP_DP = 10
    }
}