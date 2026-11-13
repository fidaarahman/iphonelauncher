package com.example.launcherapp.views

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import com.example.launcherapp.R

/**
 * Square-cell grid for the Control Center panel (iOS style). The cell size comes from the width:
 * unit = (width - gaps) / columns, and every cell is unit x unit - so tiles keep the Figma
 * proportions on any screen width.
 *
 * Each child says where it goes with android:tag="column,row" or "column,row,spanX,spanY"
 * (e.g. "0,0,2,2" = a 2x2 tile in the top-left). The grid's height is as many rows as the
 * children use. Columns and gap: app:ccColumns / app:ccGap.
 */
class ControlCenterGrid @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    var columns = DEFAULT_COLUMNS
        set(value) {
            field = value.coerceAtLeast(1)
            requestLayout()
        }

    var gap = 0
        set(value) {
            field = value.coerceAtLeast(0)
            requestLayout()
        }

    private var unit = 0

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.ControlCenterGrid, defStyleAttr, 0)
        columns = a.getInt(R.styleable.ControlCenterGrid_ccColumns, DEFAULT_COLUMNS)
        gap = a.getDimensionPixelSize(R.styleable.ControlCenterGrid_ccGap, 0)
        a.recycle()
    }

    private data class Spec(val column: Int, val row: Int, val spanX: Int, val spanY: Int)

    private fun specOf(child: View): Spec {
        val parts = (child.tag as? String)
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            .orEmpty()
        return Spec(
            column = parts.getOrElse(0) { 0 },
            row = parts.getOrElse(1) { 0 },
            spanX = parts.getOrElse(2) { 1 }.coerceAtLeast(1),
            spanY = parts.getOrElse(3) { 1 }.coerceAtLeast(1)
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = width - paddingLeft - paddingRight
        unit = ((inner - gap * (columns - 1)) / columns).coerceAtLeast(0)

        var rows = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val spec = specOf(child)
            rows = maxOf(rows, spec.row + spec.spanY)
            val childWidth = unit * spec.spanX + gap * (spec.spanX - 1)
            val childHeight = unit * spec.spanY + gap * (spec.spanY - 1)
            child.measure(
                MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY)
            )
        }
        val contentHeight = if (rows > 0) unit * rows + gap * (rows - 1) else 0
        val height = resolveSize(paddingTop + paddingBottom + contentHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val spec = specOf(child)
            val left = paddingLeft + spec.column * (unit + gap)
            val top = paddingTop + spec.row * (unit + gap)
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }

    private companion object {
        const val DEFAULT_COLUMNS = 4
    }
}
