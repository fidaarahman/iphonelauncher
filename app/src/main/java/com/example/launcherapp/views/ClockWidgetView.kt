package com.example.launcherapp.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withRotation
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class ClockWidgetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ---- Dark clock (default, no theme) ----
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DEFAULT_TILE }
    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DEFAULT_DIAL }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DEFAULT_CAPTION
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val secondPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DEFAULT_SECOND_HAND
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // ---- Themed clock (theme pack active): theme-colour tile, everything else white ----
    private val themeTilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val themeHourTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }
    private val themeMinuteTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(Color.WHITE, THEME_MINUTE_TICK_ALPHA)
        strokeCap = Paint.Cap.ROUND
    }
    private val themeNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val themeCityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(Color.WHITE, THEME_CAPTION_ALPHA)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val themeHandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val themeSecondPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Theme colour (tile of the themed clock), or null = original black clock. */
    private var themeColor: Int? = null


    fun setThemeColor(color: Int?) {
        if (color == themeColor) return
        themeColor = color
        if (color != null) themeTilePaint.color = color
        invalidate()
    }

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
        if (side <= 0f) return
        val cx = w / 2f
        val cy = h / 2f

        val now = Calendar.getInstance()
        val seconds = now.get(Calendar.SECOND).toFloat()
        val minutes = now.get(Calendar.MINUTE) + seconds / 60f
        val hours = now.get(Calendar.HOUR) + minutes / 60f

        if (themeColor != null) {
            drawThemedClock(canvas, w, h, side, cx, cy, hours, minutes, seconds)
        } else {
            drawDarkClock(canvas, w, h, side, cx, cy, hours, minutes, seconds)
        }
    }

    /** The original black clock (unchanged). */
    private fun drawDarkClock(
        canvas: Canvas, w: Float, h: Float, side: Float, cx: Float, cy: Float,
        hours: Float, minutes: Float, seconds: Float
    ) {
        // Tile + dial
        val corner = side * TILE_CORNER
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
        drawHand(canvas, cx, cy, hours * 30f, radius * 0.52f, side * 0.045f, handPaint)
        drawHand(canvas, cx, cy, minutes * 6f, radius * 0.82f, side * 0.040f, handPaint)
        dotPaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, side * 0.032f, dotPaint)

        secondPaint.strokeWidth = side * 0.012f
        canvas.withRotation(seconds * 6f, cx, cy) {
            drawLine(cx, cy + radius * 0.18f, cx, cy - radius * 0.90f, secondPaint)
        }
        dotPaint.color = secondPaint.color
        canvas.drawCircle(cx, cy, side * 0.022f, dotPaint)
        dotPaint.color = DEFAULT_TILE
        canvas.drawCircle(cx, cy, side * 0.009f, dotPaint)
    }

    /** Theme pack clock: theme-colour tile, white ticks / 12 3 6 9 / hands (like the reference app). */
    private fun drawThemedClock(
        canvas: Canvas, w: Float, h: Float, side: Float, cx: Float, cy: Float,
        hours: Float, minutes: Float, seconds: Float
    ) {
        // Theme-colour rounded tile (same corner as the black clock, so the widget keeps its shape).
        val corner = side * TILE_CORNER
        canvas.drawRoundRect(0f, 0f, w, h, corner, corner, themeTilePaint)

        // 60 ticks; every 5th is an hour tick (longer and thicker).
        val outer = side * 0.45f
        for (i in 0 until 60) {
            val isHour = i % 5 == 0
            val paint = if (isHour) themeHourTickPaint else themeMinuteTickPaint
            paint.strokeWidth = side * if (isHour) 0.018f else 0.009f
            val length = side * if (isHour) 0.07f else 0.035f
            canvas.withRotation(i * 6f, cx, cy) {
                drawLine(cx, cy - outer, cx, cy - outer + length, paint)
            }
        }

        // 12 / 3 / 6 / 9
        themeNumberPaint.textSize = side * 0.17f
        val numberRadius = side * 0.31f
        val centerOffset = -(themeNumberPaint.descent() + themeNumberPaint.ascent()) / 2f
        canvas.drawText("12", cx, cy - numberRadius + centerOffset, themeNumberPaint)
        canvas.drawText("3", cx + numberRadius, cy + centerOffset, themeNumberPaint)
        canvas.drawText("6", cx, cy + numberRadius + centerOffset, themeNumberPaint)
        canvas.drawText("9", cx - numberRadius, cy + centerOffset, themeNumberPaint)

        // City code between the 12 and the centre.
        themeCityPaint.textSize = side * 0.08f
        canvas.drawText(cityCode(), cx, cy - side * 0.11f, themeCityPaint)

        // Hands (white)
        drawHand(canvas, cx, cy, hours * 30f, side * 0.24f, side * 0.045f, themeHandPaint)
        drawHand(canvas, cx, cy, minutes * 6f, side * 0.37f, side * 0.04f, themeHandPaint)
        dotPaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, side * 0.03f, dotPaint)

        // Thin white second hand
        themeSecondPaint.strokeWidth = side * 0.01f
        canvas.withRotation(seconds * 6f, cx, cy) {
            drawLine(cx, cy + side * 0.08f, cx, cy - side * 0.41f, themeSecondPaint)
        }
        // Tiny theme-colour dot in the very centre.
        dotPaint.color = themeTilePaint.color
        canvas.drawCircle(cx, cy, side * 0.01f, dotPaint)
    }

    /** iOS-style hand: thin stem near the centre, then the thick blade. */
    private fun drawHand(
        canvas: Canvas, cx: Float, cy: Float, degrees: Float,
        length: Float, thickness: Float, paint: Paint
    ) {
        canvas.withRotation(degrees, cx, cy) {
            val stemEnd = cy - length * 0.22f
            paint.strokeWidth = thickness * 0.4f
            drawLine(cx, cy, cx, stemEnd, paint)
            paint.strokeWidth = thickness
            drawLine(cx, stemEnd, cx, cy - length, paint)
        }
    }

    private fun cityCode(): String =
        TimeZone.getDefault().id
            .substringAfterLast('/')
            .replace("_", "")
            .take(3)
            .uppercase(Locale.ROOT)

    private companion object {
        const val TILE_CORNER = 0.22f
        const val DEFAULT_TILE = 0xFF161618.toInt()
        const val DEFAULT_DIAL = 0xFF1F1F21.toInt()
        const val DEFAULT_CAPTION = 0xFF8E8E93.toInt()
        const val DEFAULT_SECOND_HAND = 0xFFFF9F0A.toInt()
        const val THEME_MINUTE_TICK_ALPHA = 170
        const val THEME_CAPTION_ALPHA = 200
    }
}