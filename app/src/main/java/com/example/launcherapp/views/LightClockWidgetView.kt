package com.example.launcherapp.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.withRotation
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min


class LightClockWidgetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val hourTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1C1C1E.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private val minuteTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAEAEB2.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val cityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8E8E93.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val secondPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF9500.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    // Also called on attach: starts/pauses the ticker with the window's visibility.
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        removeCallbacks(ticker)
        if (visibility == VISIBLE) post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val side = min(w, h)
        if (side <= 0f) return
        val cx = w / 2f
        val cy = h / 2f

        // White rounded tile (same corner ratio as the Figma tiles).
        val corner = side * 0.2f
        canvas.drawRoundRect(0f, 0f, w, h, corner, corner, facePaint)

        // 60 ticks; every 5th is an hour tick.
        val outer = side * 0.45f
        for (i in 0 until 60) {
            val isHour = i % 5 == 0
            val paint = if (isHour) hourTickPaint else minuteTickPaint
            paint.strokeWidth = side * if (isHour) 0.016f else 0.009f
            val length = side * if (isHour) 0.06f else 0.035f
            canvas.withRotation(i * 6f, cx, cy) {
                drawLine(cx, cy - outer, cx, cy - outer + length, paint)
            }
        }

        // 12 / 3 / 6 / 9
        numberPaint.textSize = side * 0.17f
        val numberRadius = side * 0.31f
        val centerOffset = -(numberPaint.descent() + numberPaint.ascent()) / 2f
        canvas.drawText("12", cx, cy - numberRadius + centerOffset, numberPaint)
        canvas.drawText("3", cx + numberRadius, cy + centerOffset, numberPaint)
        canvas.drawText("6", cx, cy + numberRadius + centerOffset, numberPaint)
        canvas.drawText("9", cx - numberRadius, cy + centerOffset, numberPaint)

        // City code between the 12 and the centre, like "NYC" in Figma.
        cityPaint.textSize = side * 0.08f
        canvas.drawText(cityCode(), cx, cy - side * 0.11f, cityPaint)

        // Hands
        val now = Calendar.getInstance()
        val seconds = now.get(Calendar.SECOND).toFloat()
        val minutes = now.get(Calendar.MINUTE) + seconds / 60f
        val hours = now.get(Calendar.HOUR) + minutes / 60f
        drawHand(canvas, cx, cy, hours * 30f, side * 0.24f, side * 0.045f)
        drawHand(canvas, cx, cy, minutes * 6f, side * 0.37f, side * 0.04f)
        dotPaint.color = 0xFF000000.toInt()
        canvas.drawCircle(cx, cy, side * 0.03f, dotPaint)

        secondPaint.strokeWidth = side * 0.012f
        canvas.withRotation(seconds * 6f, cx, cy) {
            drawLine(cx, cy + side * 0.08f, cx, cy - side * 0.41f, secondPaint)
        }
        dotPaint.color = secondPaint.color
        canvas.drawCircle(cx, cy, side * 0.02f, dotPaint)
        dotPaint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, cy, side * 0.008f, dotPaint)
    }

    /** iOS-style hand: thin stem near the centre, then the thick blade. */
    private fun drawHand(canvas: Canvas, cx: Float, cy: Float, degrees: Float, length: Float, thickness: Float) {
        canvas.withRotation(degrees, cx, cy) {
            val stemEnd = cy - length * 0.22f
            handPaint.strokeWidth = thickness * 0.4f
            drawLine(cx, cy, cx, stemEnd, handPaint)
            handPaint.strokeWidth = thickness
            drawLine(cx, stemEnd, cx, cy - length, handPaint)
        }
    }

    private fun cityCode(): String =
        TimeZone.getDefault().id
            .substringAfterLast('/')
            .replace("_", "")
            .take(3)
            .uppercase(Locale.ROOT)
}