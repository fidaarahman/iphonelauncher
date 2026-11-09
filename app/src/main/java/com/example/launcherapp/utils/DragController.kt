package com.example.launcherapp.utils

import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.viewpager2.widget.ViewPager2
import com.example.launcherapp.ui.model.HomeLocation
import com.example.launcherapp.ui.model.HomeModel
import com.example.launcherapp.views.HomeCellLayout
import kotlin.math.min


class DragController(
    private val pager: ViewPager2,
    private val dock: ViewGroup,
    private val dragLayer: FrameLayout,
    private val host: Host
) {

    interface Host {
        val model: HomeModel
        fun appIcon(key: String): Drawable?
        fun pageCells(page: Int): HomeCellLayout?
        fun refreshPage(page: Int)
        fun refreshDock()
        fun onPageCountChanged()
        fun onDragFinished(changed: Boolean)
    }

    private sealed interface Hover {
        data class Page(val page: Int, val index: Int) : Hover
        data class Dock(val index: Int) : Hover
    }

    var isDragging = false
        private set

    private var draggedKey: String? = null
    private var origin: HomeLocation? = null
    private var hover: Hover? = null
    private var floatingIcon: ImageView? = null

    private var touchOffsetX = 0f
    private var touchOffsetY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f

    private val density = dragLayer.resources.displayMetrics.density
    private val edgeZonePx = EDGE_ZONE_DP * density
    private var pendingFlip = 0 // -1 = previous page, +1 = next page, 0 = none
    private val flipRunnable = Runnable { flipPage() }

    fun startDrag(key: String, sourceIcon: View, rawX: Float, rawY: Float) {
        if (isDragging) return
        val icon = host.appIcon(key) ?: return

        val sourceLocation = IntArray(2)
        sourceIcon.getLocationOnScreen(sourceLocation)
        touchOffsetX = rawX - sourceLocation[0]
        touchOffsetY = rawY - sourceLocation[1]
        sourceIcon.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        val origin = host.model.remove(key) ?: return
        isDragging = true
        draggedKey = key
        this.origin = origin
        hover = null
        lastRawX = rawX
        lastRawY = rawY

        floatingIcon = ImageView(dragLayer.context).apply {
            // Own copy of the drawable so the floating icon and grid icons don't share bounds.
            setImageDrawable(icon.constantState?.newDrawable()?.mutate() ?: icon)
            layoutParams = FrameLayout.LayoutParams(sourceIcon.width, sourceIcon.height)
        }.also { floating ->
            dragLayer.addView(floating)
            moveFloatingIcon(rawX, rawY)
            floating.animate().scaleX(LIFT_SCALE).scaleY(LIFT_SCALE).setDuration(120L).start()
        }
        pager.isUserInputEnabled = false

        refreshLocation(origin)
        updateHover(rawX, rawY)
    }

    fun onTouchEvent(event: MotionEvent) {
        if (!isDragging) return
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                lastRawX = event.rawX
                lastRawY = event.rawY
                moveFloatingIcon(event.rawX, event.rawY)
                updateHover(event.rawX, event.rawY)
                checkScreenEdges(event.rawX, event.rawY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drop()
        }
    }

    /** Releases the icon at the current target spot (or back where it came from). */
    fun drop() {
        if (!isDragging) return
        val key = draggedKey ?: return
        val model = host.model
        when (val target = hover) {
            is Hover.Page -> model.insertIntoPage(target.page, target.index, key)
            is Hover.Dock -> if (!model.insertIntoDock(target.index, key)) origin?.let { model.restore(it, key) }
            null -> origin?.let { model.restore(it, key) }
        }
        finish(changed = true)
    }

    /** Puts the icon back exactly where it was (e.g. Home pressed mid-drag). */
    fun cancelDrag() {
        if (!isDragging) return
        val key = draggedKey
        val origin = origin
        if (key != null && origin != null) host.model.restore(origin, key)
        finish(changed = false)
    }

    /** Page contents with an empty slot where the dragged icon would land, or null if not targeted. */
    fun previewPage(page: Int): List<String?>? {
        val target = hover as? Hover.Page ?: return null
        if (target.page != page) return null
        val keys = host.model.pages.getOrNull(page).orEmpty().toMutableList<String?>()
        keys.add(target.index.coerceIn(0, keys.size), null)
        return keys
    }

    /** Dock contents with an empty slot where the dragged icon would land, or null if not targeted. */
    fun previewDock(): List<String?>? {
        val target = hover as? Hover.Dock ?: return null
        val keys = host.model.dock.toMutableList<String?>()
        keys.add(target.index.coerceIn(0, keys.size), null)
        return keys
    }

    private fun finish(changed: Boolean) {
        dragLayer.removeCallbacks(flipRunnable)
        pendingFlip = 0
        floatingIcon?.let { floating ->
            floating.animate().cancel()
            dragLayer.removeView(floating)
        }
        floatingIcon = null
        isDragging = false
        draggedKey = null
        origin = null
        hover = null
        pager.isUserInputEnabled = true
        host.onDragFinished(changed)
    }

    private fun moveFloatingIcon(rawX: Float, rawY: Float) {
        val floating = floatingIcon ?: return
        val layerLocation = IntArray(2)
        dragLayer.getLocationOnScreen(layerLocation)
        floating.translationX = rawX - touchOffsetX - layerLocation[0]
        floating.translationY = rawY - touchOffsetY - layerLocation[1]
    }

    private fun updateHover(rawX: Float, rawY: Float) {
        val newHover = hoverAt(rawX, rawY)
        if (newHover == hover) return
        val oldHover = hover
        hover = newHover
        refreshHover(oldHover)
        val samePage = oldHover is Hover.Page && newHover is Hover.Page && oldHover.page == newHover.page
        val bothDock = oldHover is Hover.Dock && newHover is Hover.Dock
        if (!samePage && !bothDock) refreshHover(newHover)
    }

    private fun hoverAt(rawX: Float, rawY: Float): Hover? {
        if (isInside(dock, rawX, rawY)) return dockHoverAt(rawX)

        val page = pager.currentItem
        val keepCurrent = (hover as? Hover.Page)?.takeIf { it.page == page }
        val cells = host.pageCells(page) ?: return keepCurrent
        val cellsLocation = IntArray(2)
        cells.getLocationOnScreen(cellsLocation)
        val cell = cells.cellAt(rawX - cellsLocation[0], rawY - cellsLocation[1]) ?: return keepCurrent

        val slotIndex = cells.iconCells().indexOf(cell)
        if (slotIndex < 0) return keepCurrent // over the clock widget
        val appsOnPage = host.model.pages.getOrNull(page)?.size ?: 0
        // Dropping below the last icon puts the app at the end - pages never have gaps.
        return Hover.Page(page, min(slotIndex, appsOnPage))
    }

    private fun dockHoverAt(rawX: Float): Hover? {
        val model = host.model
        if (!model.canInsertIntoDock()) return null // dock full: icon goes back if released here
        val dockLocation = IntArray(2)
        dock.getLocationOnScreen(dockLocation)
        val innerWidth = (dock.width - dock.paddingLeft - dock.paddingRight).coerceAtLeast(1)
        val slotCount = model.dock.size + 1
        val x = rawX - dockLocation[0] - dock.paddingLeft
        val index = (x / (innerWidth.toFloat() / slotCount)).toInt().coerceIn(0, model.dock.size)
        return Hover.Dock(index)
    }

    private fun checkScreenEdges(rawX: Float, rawY: Float) {
        val direction = when {
            isInside(dock, rawX, rawY) -> 0
            rawX < edgeZonePx -> -1
            rawX > dragLayer.width - edgeZonePx -> 1
            else -> 0
        }
        if (direction == pendingFlip) return
        dragLayer.removeCallbacks(flipRunnable)
        pendingFlip = direction
        if (direction != 0) dragLayer.postDelayed(flipRunnable, FLIP_DELAY_MS)
    }

    private fun flipPage() {
        val direction = pendingFlip
        pendingFlip = 0
        if (!isDragging || direction == 0) return

        val target = pager.currentItem + direction
        if (target < 0) return
        val model = host.model
        if (target >= model.pages.size) {
            // Past the last page: create a new one (removed again on drop if left empty).
            if (model.pages.last().isEmpty()) return
            model.pages.add(mutableListOf())
            host.onPageCountChanged()
        }

        val oldHover = hover
        hover = null
        refreshHover(oldHover)
        pager.setCurrentItem(target, true)
        dragLayer.postDelayed({
            if (isDragging) {
                updateHover(lastRawX, lastRawY)
                checkScreenEdges(lastRawX, lastRawY) // still at the edge -> keep flipping
            }
        }, PAGE_SETTLE_MS)
    }

    private fun refreshLocation(location: HomeLocation) {
        when (location) {
            is HomeLocation.Page -> host.refreshPage(location.page)
            is HomeLocation.Dock -> host.refreshDock()
        }
    }

    private fun refreshHover(target: Hover?) {
        when (target) {
            is Hover.Page -> host.refreshPage(target.page)
            is Hover.Dock -> host.refreshDock()
            null -> Unit
        }
    }

    private fun isInside(view: View, rawX: Float, rawY: Float): Boolean {
        if (!view.isShown) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return rawX >= location[0] && rawX <= location[0] + view.width &&
                rawY >= location[1] && rawY <= location[1] + view.height
    }

    companion object {
        private const val EDGE_ZONE_DP = 28f
        private const val FLIP_DELAY_MS = 650L
        private const val PAGE_SETTLE_MS = 350L
        private const val LIFT_SCALE = 1.15f
    }
}