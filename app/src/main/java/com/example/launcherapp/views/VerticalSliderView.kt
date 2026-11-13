package com.example.launcherapp.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * iOS Control Center slider (brightness / volume): a tall glass pill that fills from the bottom,
 * with its icon near the bottom. Drag up/down anywhere on it - the value moves by how far the
 * finger moves (like iOS), it doesn't jump to where you touched.
 */
class VerticalSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        /** Return false to cancel the drag (e.g. a permission is missing). */
        fun onStartTracking(): Boolean = true
        fun onValueChanged(value: Float)
        fun onStopTracking() {}
    }

    var listener: Listener? = null

    /** 0 (empty) .. 1 (full). */
    var value = 0.5f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    var icon: Drawable? = null
        set(v) {
            field = v?.mutate()
            field?.setTint(iconColor)
            invalidate()
        }

    var iconColor = Color.WHITE
        set(v) {
            field = v
            icon?.setTint(v)
            invalidate()
        }

    /** True while the finger is on the slider (outside updates should not move it then). */
    var isTracking = false
        private set

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TRACK_COLOR }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FILL_COLOR }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = STROKE_COLOR
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val rect = RectF()
    private var lastY = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = w * CORNER_FACTOR

        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        // Fill from the bottom: the full rounded shape, clipped to the filled part.
        val fillTop = h * (1f - value)
        canvas.save()
        canvas.clipRect(0f, fillTop, w, h)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        canvas.restore()

        val half = strokePaint.strokeWidth / 2f
        rect.set(half, half, w - half, h - half)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        icon?.let { drawable ->
            val size = minOf(w * 0.36f, ICON_MAX_DP * density).toInt()
            val left = ((w - size) / 2f).toInt()
            val top = (h - size - w * 0.26f).toInt()
            drawable.setBounds(left, top, left + size, top + size)
            drawable.draw(canvas)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (listener?.onStartTracking() == false) return false
                isTracking = true
                lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isTracking || height == 0) return false
                val dy = event.y - lastY
                lastY = event.y
                val newValue = (value - dy / height).coerceIn(0f, 1f)
                if (newValue != value) {
                    value = newValue
                    listener?.onValueChanged(value)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isTracking) {
                    isTracking = false
                    listener?.onStopTracking()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private companion object {
        const val CORNER_FACTOR = 0.42f
        const val ICON_MAX_DP = 26f
        const val TRACK_COLOR = 0x40BEEBFF
        const val FILL_COLOR = 0xFFDCE8F8.toInt()
        const val STROKE_COLOR = 0x33FFFFFF
    }
}