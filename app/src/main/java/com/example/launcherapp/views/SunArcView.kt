package com.example.launcherapp.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.PathParser
import androidx.core.graphics.withClip
import java.util.Calendar
import kotlin.math.exp

/**
 * The sun-arc from the Figma "Sunset" tile (ic_sunset.svg), drawn in code so the sun can MOVE:
 * - the curve is the exact Figma path; above the horizon it uses the Figma blue gradient,
 *   below the horizon it's the 15% black shade, with the Figma 1-unit gap as the horizon line;
 * - the sun travels along the curve: sunrise -> sunset across the visible arc, and through the
 *   low ends of the curve at night (dimmed below the horizon).
 *
 * Call [setSunTimes] with today's real sunrise/sunset; until then it assumes 06:00 / 18:00.
 * Height is always width x 41/158 (the SVG's aspect ratio), so use layout_height="wrap_content".
 */
class SunArcView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val sourceCurve: Path = PathParser.createPathFromPathData(CURVE_PATH)
    private val curve = Path()
    private val scaleMatrix = Matrix()
    private var sx = 1f
    private var sy = 1f

    private val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26000000 } // black 15%
    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var sunriseMillis: Long? = null
    private var sunsetMillis: Long? = null

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 60_000L)
        }
    }

    /** Today's sunrise and sunset (epoch millis). */
    fun setSunTimes(sunrise: Long, sunset: Long) {
        if (sunset <= sunrise) return
        sunriseMillis = sunrise
        sunsetMillis = sunset
        invalidate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        removeCallbacks(ticker)
        if (visibility == VISIBLE) post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (width * VB_HEIGHT / VB_WIDTH).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sx = w / VB_WIDTH
        sy = h / VB_HEIGHT
        scaleMatrix.setScale(sx, sy)
        scaleMatrix.preTranslate(0f, -VB_TOP)
        curve.reset()
        sourceCurve.transform(scaleMatrix, curve)
        dayPaint.shader = LinearGradient(
            GRADIENT_X1 * sx, 0f, GRADIENT_X2 * sx, 0f,
            intArrayOf(CURVE_EDGE, CURVE_MIDDLE, CURVE_EDGE),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val w = width.toFloat()
        val h = height.toFloat()

        // Curve: gradient above the horizon, dark shade below it (1-unit gap between = horizon).
        canvas.withClip(0f, 0f, w, toY(HORIZON_Y)) { drawPath(curve, dayPaint) }
        canvas.withClip(0f, toY(NIGHT_TOP_Y), w, h) { drawPath(curve, nightPaint) }

        // Sun
        val x = sunX(System.currentTimeMillis())
        val y = curveY(x)
        val px = x * sx
        val py = toY(y)
        val radius = SUN_RADIUS * sx
        val belowHorizon = y > HORIZON_Y
        val alpha = if (belowHorizon) 115 else 255

        glowPaint.shader = RadialGradient(
            px, py, radius * 3f,
            intArrayOf(0xCCFFFFFF.toInt(), 0x00FFFFFF),
            null,
            Shader.TileMode.CLAMP
        )
        glowPaint.alpha = alpha
        canvas.drawCircle(px, py, radius * 3f, glowPaint)
        sunPaint.alpha = alpha
        canvas.drawCircle(px, py, radius, sunPaint)
    }

    /** Viewbox y -> view pixels. */
    private fun toY(viewBoxY: Float) = (viewBoxY - VB_TOP) * sy

    /**
     * Sun's x on the curve (viewbox units). Daytime moves from the left horizon crossing to the
     * right one; night continues off the right end and comes back in from the left end.
     */
    private fun sunX(now: Long): Float {
        val (riseMs, setMs) = todaysSunTimes()
        if (now in riseMs..setMs) {
            val fraction = (now - riseMs).toFloat() / (setMs - riseMs)
            return SUNRISE_X + (SUNSET_X - SUNRISE_X) * fraction
        }
        val nightLength = DAY_MS - (setMs - riseMs)
        val sinceSunset = if (now > setMs) now - setMs else now - (setMs - DAY_MS)
        val fraction = (sinceSunset.toFloat() / nightLength).coerceIn(0f, 1f)
        var x = SUNSET_X + (VB_WIDTH - SUNSET_X + SUNRISE_X) * fraction
        if (x > VB_WIDTH) x -= VB_WIDTH
        return x
    }

    private fun todaysSunTimes(): Pair<Long, Long> {
        val riseMs = sunriseMillis
        val setMs = sunsetMillis
        if (riseMs != null && setMs != null) return riseMs to setMs
        fun today(hour: Int) = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return today(DEFAULT_SUNRISE_HOUR) to today(DEFAULT_SUNSET_HOUR)
    }

    /** Centre line of the Figma curve (a bell shape fitted to the SVG path), viewbox units. */
    private fun curveY(x: Float): Float {
        val dx = x - PEAK_X
        return BASE_Y - PEAK_HEIGHT * exp(-(dx * dx) / (2f * SIGMA_SQUARED))
    }

    private companion object {
        // Figma export ic_sunset.svg: the curve's mask path and its visible area (0..158 x 15.8..56.8).
        const val CURVE_PATH =
            "M108.991 30.0575L105.852 27.8375C89.76 16.4552 68.24 16.4552 52.1479 27.8375L49.0093 30.0575L44.061 33.5575H44.0517L36.8732 38.6349C25.6435 46.5779 9.95548 57.6677 -3 55.3008V51.8008C9.2337 54.2106 24.2262 43.2934 34.8521 35.7775L37.9907 33.5575L42.939 30.0575L42.9483 30.0575L50.1268 24.98C67.4301 12.741 90.5699 12.741 107.873 24.98L115.052 30.0575L120.009 33.5575L123.148 35.7775C133.774 43.2933 148.766 54.2106 161 51.8008V55.3008C148.045 57.6677 132.357 46.5779 121.127 38.6349L113.948 33.5575H113.939L108.991 30.0575Z"
        const val VB_WIDTH = 158f
        const val VB_TOP = 15.8f
        const val VB_HEIGHT = 41f
        const val HORIZON_Y = 33.8f    // bottom of the gradient rect in the SVG
        const val NIGHT_TOP_Y = 34.8f  // top of the 15% black rect in the SVG
        const val GRADIENT_X1 = 38.5f
        const val GRADIENT_X2 = 119f
        const val CURVE_EDGE = 0xFF6F99C1.toInt()
        const val CURVE_MIDDLE = 0xFF96B1CE.toInt()
        const val SUN_RADIUS = 4.5f

        // Bell fit of the curve's centre line: peak near x=79, y=18; ends near y=53.
        const val PEAK_X = 79f
        const val BASE_Y = 56f
        const val PEAK_HEIGHT = 38f
        const val SIGMA_SQUARED = 1273f
        const val SUNRISE_X = 41.2f    // where the centre line crosses the horizon on the left
        const val SUNSET_X = 116.8f    // ...and on the right

        const val DAY_MS = 24L * 60 * 60 * 1000
        const val DEFAULT_SUNRISE_HOUR = 6
        const val DEFAULT_SUNSET_HOUR = 18
    }
}