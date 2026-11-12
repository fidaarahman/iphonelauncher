package com.example.launcherapp.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Full-screen dark scrim (the Google-folder / App-Library "open group" dialog's dimmed backdrop)
 * that can have a rounded-rect hole cut out of it. Needed so a blurred panel sitting on top can
 * sample the UNDIMMED content behind it (BlurView redraws this view's whole subtree to capture
 * what's behind the panel, dark fill included, unless that fill has an actual hole in it) - while
 * everything else on screen still gets dimmed normally. Purely a drawing effect: it doesn't change
 * touch handling, so taps on the "hole" area still reach whatever real view is drawn there.
 */
class HolePunchScrimView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SCRIM_COLOR }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private var hole: RectF? = null
    private var holeRadiusPx = 0f

    init {
        setWillNotDraw(false)
    }

    /** [rect] in this view's own coordinates, or null to go back to a plain, full scrim. */
    fun setHole(rect: RectF?, radiusPx: Float) {
        hole = rect
        holeRadiusPx = radiusPx
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        hole?.let { canvas.drawRoundRect(it, holeRadiusPx, holeRadiusPx, holePaint) }
        canvas.restoreToCount(layer)
        super.dispatchDraw(canvas)
    }

    private companion object {
        const val SCRIM_COLOR = 0xB3000000.toInt()
    }
}
