package com.example.launcherapp.ui.views

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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Live analog clock styled like the iOS Clock widget in the Figma design: dark rounded tile,
 * white numerals, white hour/minute hands, orange second hand and a small city code
 * (e.g. "KAR" for Asia/Karachi). Everything is drawn in code, so it scales to any size and
 * shows the real time instead of a static image.
 */
class ClockWidgetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF161618.toInt() }
    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F1F21.toInt() }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8E8E93.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val secondPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF9F0A.toInt()
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

    // Called on attach as well, so this both starts and pauses the ticker:
    // the clock only redraws while the home screen is actually visible.
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
        val cx = w / 2f
        val cy = h / 2f

        // Tile + dial
        val corner = side * 0.22f
        canvas.drawRoundRect(0f, 0f, w, h, corner, corner, tilePaint)
        val radius = side * 0.46f
        canvas.drawCircle(cx, cy, radius, dialPaint)

        // Numerals 1..12
        numberPaint.textSize = side * 0.115f
        val numberRadius = radius * 0.80f
        val numberYOffset = -(numberPaint.descent() + numberPaint.ascent()) / 2f
        for (n in 1..12) {
            val angle = Math.toRadians(n * 30.0 - 90.0)
            val x = cx + (cos(angle) * numberRadius).toFloat()
            val y = cy + (sin(angle) * numberRadius).toFloat() + numberYOffset
            canvas.drawText(n.toString(), x, y, numberPaint)
        }

        // City code under the centre, like the iOS widget
        captionPaint.textSize = side * 0.07f
        canvas.drawText(cityCode(), cx, cy + radius * 0.45f, captionPaint)

        // Hands
        val now = Calendar.getInstance()
        val seconds = now.get(Calendar.SECOND).toFloat()
        val minutes = now.get(Calendar.MINUTE) + seconds / 60f
        val hours = now.get(Calendar.HOUR) + minutes / 60f

        drawHand(canvas, cx, cy, hours * 30f, radius * 0.52f, side * 0.045f)
        drawHand(canvas, cx, cy, minutes * 6f, radius * 0.82f, side * 0.040f)
        dotPaint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, cy, side * 0.032f, dotPaint)

        secondPaint.strokeWidth = side * 0.012f
        canvas.withRotation(seconds * 6f, cx, cy) {
            drawLine(cx, cy + radius * 0.18f, cx, cy - radius * 0.90f, secondPaint)
        }
        dotPaint.color = secondPaint.color
        canvas.drawCircle(cx, cy, side * 0.022f, dotPaint)
        dotPaint.color = 0xFF161618.toInt()
        canvas.drawCircle(cx, cy, side * 0.009f, dotPaint)
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
