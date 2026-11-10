package com.example.launcherapp.views

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.launcherapp.R
import kotlin.math.min

/**
 * Battery tile ring from Figma: a green ring around the phone icon (ic_phone), filled to the
 * battery level (red below 20%). Keeps itself up to date while on screen and also updates the
 * "64%" text next to it (the sibling TextView with id batteryPercent), so the activity doesn't
 * have to refresh anything.
 */
class BatteryRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 0..100, or -1 while unknown. */
    var level: Int = -1
        private set

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFE5E5EA.toInt()
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ring = RectF()
    private val phoneIcon: Drawable? =
        ContextCompat.getDrawable(context, R.drawable.ic_phone)?.mutate()?.apply { setTint(Color.BLACK) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = update(intent)
    }
    private var registered = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // ACTION_BATTERY_CHANGED is sticky: registering returns the current state immediately.
        val current = ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        registered = true
        current?.let(::update)
    }

    override fun onDetachedFromWindow() {
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
        super.onDetachedFromWindow()
    }

    private fun update(intent: Intent) {
        val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (raw < 0 || scale <= 0) return
        level = (raw * 100 / scale).coerceIn(0, 100)
        (parent as? View)?.findViewById<TextView>(R.id.batteryPercent)?.text = "$level%"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat()
        if (side <= 0f) return
        val stroke = side * 0.1f
        trackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke
        progressPaint.color = if (level in 0 until LOW_LEVEL) RED else GREEN

        val cx = width / 2f
        val cy = height / 2f
        val radius = side / 2f - stroke / 2f
        ring.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(ring, 0f, 360f, false, trackPaint)
        if (level > 0) canvas.drawArc(ring, -90f, 360f * level / 100f, false, progressPaint)

        // Phone icon in the middle, keeping its aspect ratio.
        phoneIcon?.let { icon ->
            val iconHeight = side * 0.46f
            val ratio = if (icon.intrinsicHeight > 0) icon.intrinsicWidth.toFloat() / icon.intrinsicHeight else 0.6f
            val iconWidth = iconHeight * ratio
            icon.setBounds(
                (cx - iconWidth / 2f).toInt(), (cy - iconHeight / 2f).toInt(),
                (cx + iconWidth / 2f).toInt(), (cy + iconHeight / 2f).toInt()
            )
            icon.draw(canvas)
        }
    }

    private companion object {
        const val LOW_LEVEL = 20
        const val GREEN = 0xFF34C759.toInt()
        const val RED = 0xFFFF3B30.toInt()
    }
}