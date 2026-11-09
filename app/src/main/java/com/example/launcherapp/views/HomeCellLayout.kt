package com.example.launcherapp.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import com.example.launcherapp.R
import kotlin.math.min

/**
 * A fixed iOS-style home grid (default 4 columns x 6 rows).
 *
 * Unlike GridView it never scrolls, so a page never shows a half-cut row, and a child can
 * span several cells in both directions - which is what lets the 2x2 clock widget sit in the
 * top-left corner with apps flowing to its right, exactly like the Figma design.
 *
 * Cell size = available space / columns (or rows). App cells fill their whole cell; widget
 * cells are inset horizontally so the widget's left/right edges line up with the icons in the
 * columns it covers.
 */
class HomeCellLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    var columns = DEFAULT_COLUMNS
        set(value) {
            field = value
            requestLayout()
        }

    var rows = DEFAULT_ROWS
        set(value) {
            field = value
            requestLayout()
        }

    private val iconSize = resources.getDimensionPixelSize(R.dimen.home_icon_size)

    /**
     * Rows never get taller than this (iOS keeps a fixed row spacing). On tall screens the
     * rows stay packed at the top and the spare space ends up above the search pill,
     * like the Figma design, instead of the grid stretching over the whole page.
     */
    private val maxCellHeight = resources.getDimensionPixelSize(R.dimen.home_cell_max_height)

    private var cellWidth = 0
    private var cellHeight = 0

    /** Cells not covered by any child, in reading order (row by row, left to right). */
    fun freeCells(): List<Pair<Int, Int>> {
        val taken = Array(rows) { BooleanArray(columns) }
        for (i in 0 until childCount) {
            val lp = getChildAt(i).layoutParams as LayoutParams
            for (y in lp.cellY until min(rows, lp.cellY + lp.spanY)) {
                for (x in lp.cellX until min(columns, lp.cellX + lp.spanX)) {
                    taken[y][x] = true
                }
            }
        }
        return buildList {
            for (y in 0 until rows) for (x in 0 until columns) if (!taken[y][x]) add(x to y)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        cellWidth = (width - paddingLeft - paddingRight) / columns
        cellHeight = min((height - paddingTop - paddingBottom) / rows, maxCellHeight)

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as LayoutParams
            if (lp.isWidget) sizeWidgetBody(child, lp)
            child.measure(
                MeasureSpec.makeMeasureSpec(childWidth(lp), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(lp.spanY * cellHeight, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val isRtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as LayoutParams
            val inset = if (lp.isWidget) widgetInset() else 0
            val left = if (isRtl) {
                width - paddingRight - (lp.cellX + lp.spanX) * cellWidth + inset
            } else {
                paddingLeft + lp.cellX * cellWidth + inset
            }
            val top = paddingTop + lp.cellY * cellHeight
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }

    /** Horizontal gap between a cell's edge and the icon centred inside it. */
    private fun widgetInset() = ((cellWidth - iconSize) / 2).coerceAtLeast(0)

    private fun childWidth(lp: LayoutParams) =
        if (lp.isWidget) lp.spanX * cellWidth - 2 * widgetInset() else lp.spanX * cellWidth

    /**
     * Sizes the widget's square body (view with id `widgetBody`) so its top lines up with the
     * first row's icons and its bottom with the last covered row's icons. The widget's label
     * then lands on the same line as the app labels next to it.
     */
    private fun sizeWidgetBody(child: View, lp: LayoutParams) {
        val body = child.findViewById<View>(R.id.widgetBody) ?: return
        val side = min(childWidth(lp), (lp.spanY - 1) * cellHeight + iconSize)
        body.layoutParams.width = side
        body.layoutParams.height = side
    }

    override fun shouldDelayChildPressedState() = false

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p is LayoutParams

    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = LayoutParams(0, 0)

    override fun generateLayoutParams(attrs: AttributeSet?): ViewGroup.LayoutParams =
        LayoutParams(context, attrs)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams =
        if (p != null) LayoutParams(p) else generateDefaultLayoutParams()

    class LayoutParams : ViewGroup.LayoutParams {
        var cellX = 0
        var cellY = 0
        var spanX = 1
        var spanY = 1
        var isWidget = false

        constructor(
            cellX: Int,
            cellY: Int,
            spanX: Int = 1,
            spanY: Int = 1,
            isWidget: Boolean = false
        ) : super(MATCH_PARENT, MATCH_PARENT) {
            this.cellX = cellX
            this.cellY = cellY
            this.spanX = spanX
            this.spanY = spanY
            this.isWidget = isWidget
        }

        constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

        constructor(source: ViewGroup.LayoutParams) : super(source)
    }

    companion object {
        const val DEFAULT_COLUMNS = 4
        const val DEFAULT_ROWS = 6
    }
}