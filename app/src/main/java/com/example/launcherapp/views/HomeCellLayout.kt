package com.example.launcherapp.views

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import androidx.core.view.isGone
import com.example.launcherapp.ui.model.HomeGrid

class HomeCellLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    var columns: Int = HomeGrid.COLUMNS
    var rows: Int = HomeGrid.ROWS

    /**
     * Returns list of free/available icon cell coordinates (x, y) in row-major order.
     * Excludes cells occupied by widgets.
     */
    fun iconCells(): List<Pair<Int, Int>> {
        val occupied = HashSet<Pair<Int, Int>>()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as? LayoutParams ?: continue
            if (lp.isWidget) {
                for (x in lp.cellX until (lp.cellX + lp.spanX)) {
                    for (y in lp.cellY until (lp.cellY + lp.spanY)) {
                        occupied.add(Pair(x, y))
                    }
                }
            }
        }
        val result = ArrayList<Pair<Int, Int>>()
        for (y in 0 until rows) {
            for (x in 0 until columns) {
                val cell = Pair(x, y)
                if (!occupied.contains(cell)) {
                    result.add(cell)
                }
            }
        }
        return result
    }

    /**
     * Returns the grid coordinate (cellX, cellY) containing pixel position (x, y)
     * relative to this HomeCellLayout, or null if out of bounds.
     */
    fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom
        if (availableWidth <= 0 || availableHeight <= 0) return null

        val relX = x - paddingLeft
        val relY = y - paddingTop
        if (relX < 0 || relY < 0 || relX >= availableWidth || relY >= availableHeight) return null

        val cellWidth = availableWidth.toFloat() / columns
        val cellHeight = availableHeight.toFloat() / rows

        val col = (relX / cellWidth).toInt().coerceIn(0, columns - 1)
        val row = (relY / cellHeight).toInt().coerceIn(0, rows - 1)
        return Pair(col, row)
    }

    fun cellAt(x: Int, y: Int): Pair<Int, Int>? = cellAt(x.toFloat(), y.toFloat())

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val availableHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)

        val cellWidth = if (columns > 0) availableWidth.toFloat() / columns else 0f
        val cellHeight = if (rows > 0) availableHeight.toFloat() / rows else 0f

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.isGone) continue
            val lp = child.layoutParams as? LayoutParams ?: continue
            val childWidth = (cellWidth * lp.spanX).toInt()
            val childHeight = (cellHeight * lp.spanY).toInt()
            child.measure(
                MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY)
            )
        }

        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val availableWidth = (r - l - paddingLeft - paddingRight).coerceAtLeast(0)
        val availableHeight = (b - t - paddingTop - paddingBottom).coerceAtLeast(0)

        val cellWidth = if (columns > 0) availableWidth.toFloat() / columns else 0f
        val cellHeight = if (rows > 0) availableHeight.toFloat() / rows else 0f

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.isGone) continue
            val lp = child.layoutParams as? LayoutParams ?: continue
            val left = (paddingLeft + lp.cellX * cellWidth).toInt()
            val top = (paddingTop + lp.cellY * cellHeight).toInt()
            val right = (paddingLeft + (lp.cellX + lp.spanX) * cellWidth).toInt()
            val bottom = (paddingTop + (lp.cellY + lp.spanY) * cellHeight).toInt()
            child.layout(left, top, right, bottom)
        }
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LayoutParams

    override fun generateLayoutParams(attrs: AttributeSet?): ViewGroup.LayoutParams =
        LayoutParams(context, attrs)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams =
        LayoutParams(p ?: ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams =
        LayoutParams(WRAP_CONTENT, WRAP_CONTENT)

    class LayoutParams : ViewGroup.LayoutParams {
        var cellX: Int = 0
        var cellY: Int = 0
        var spanX: Int = 1
        var spanY: Int = 1
        var isWidget: Boolean = false
        var key: String? = null

        constructor(c: Context, attrs: AttributeSet?) : super(c, attrs)
        constructor(width: Int, height: Int) : super(width, height)
        constructor(
            cellX: Int = 0,
            cellY: Int = 0,
            spanX: Int = 1,
            spanY: Int = 1,
            isWidget: Boolean = false,
            key: String? = null
        ) : super(WRAP_CONTENT, WRAP_CONTENT) {
            this.cellX = cellX
            this.cellY = cellY
            this.spanX = spanX
            this.spanY = spanY
            this.isWidget = isWidget
            this.key = key
        }
        constructor(source: ViewGroup.LayoutParams) : super(source) {
            if (source is LayoutParams) {
                this.cellX = source.cellX
                this.cellY = source.cellY
                this.spanX = source.spanX
                this.spanY = source.spanY
                this.isWidget = source.isWidget
                this.key = source.key
            }
        }
    }
}
