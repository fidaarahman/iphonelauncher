package com.example.launcherapp.ui.icons

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable


object DuotoneIconFilter {

    private const val LUT_SIZE = 256

    private const val TONE_SATURATION_FACTOR = 0.85f
    private const val TONE_MAX_SATURATION = 0.8f
    private const val TONE_BRIGHTNESS = 0.68f

    // Dark end: dark, but still carrying the theme hue (not plain black).
    private const val SHADOW_BRIGHTNESS = 0.18f

    // Light end: mostly white with a light tint of the theme colour.
    private const val HIGHLIGHT_WHITE_MIX = 0.85f

    // Less extra contrast, so more of each icon stays in the theme colour.
    private const val CONTRAST = 0.3f

    /** Same signature as before: returns a recoloured copy, the original icon is never touched. */
    fun apply(icon: Drawable, tintColor: Int, resources: Resources): Drawable =
        BitmapDrawable(resources, recolor(icon.toMutableBitmap(), tintColor, inPlace = true))


    fun recolor(source: Bitmap, tintColor: Int, inPlace: Boolean = false, amount: Float = 1f): Bitmap {
        val bitmap = if (inPlace && source.isMutable && source.config == Bitmap.Config.ARGB_8888) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true)
        }
        val lut = buildLut(tintColor)
        val mix = (amount.coerceIn(0f, 1f) * 256).toInt() // share of the themed colour (0..256)
        val keep = 256 - mix                              // share of the original colour
        val width = bitmap.width
        val height = bitmap.height
        val row = IntArray(width)
        // Row by row, so even a full-screen wallpaper doesn't need one huge pixel array.
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val pixel = row[x]
                val alpha = pixel ushr 24
                if (alpha == 0) continue
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                // Perceived lightness (Rec. 709 weights), 0..255.
                val luminance = (r * 54 + g * 183 + b * 19) shr 8
                val themed = lut[luminance]
                val nr = (r * keep + ((themed shr 16) and 0xFF) * mix) shr 8
                val ng = (g * keep + ((themed shr 8) and 0xFF) * mix) shr 8
                val nb = (b * keep + (themed and 0xFF) * mix) shr 8
                row[x] = (alpha shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        return bitmap
    }


    fun toneOf(themeColor: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(themeColor, hsv)
        hsv[1] = (hsv[1] * TONE_SATURATION_FACTOR).coerceAtMost(TONE_MAX_SATURATION)
        hsv[2] = TONE_BRIGHTNESS
        return Color.HSVToColor(hsv)
    }

    /** Dark end of the scale: almost black, with a hint of the theme hue. */
    fun shadowOf(themeColor: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(toneOf(themeColor), hsv)
        hsv[2] = SHADOW_BRIGHTNESS
        return Color.HSVToColor(hsv)
    }

    /** Light end of the scale: practically white. */
    fun highlightOf(themeColor: Int): Int = blend(toneOf(themeColor), Color.WHITE, HIGHLIGHT_WHITE_MIX)

    /** 256 colours: shadow -> muted tone (first half), muted tone -> white (second half). */
    private fun buildLut(themeColor: Int): IntArray {
        val shadow = shadowOf(themeColor)
        val tone = toneOf(themeColor)
        val highlight = highlightOf(themeColor)
        return IntArray(LUT_SIZE) { i ->
            val t = contrastCurve(i / (LUT_SIZE - 1).toFloat())
            if (t < 0.5f) blend(shadow, tone, t * 2f) else blend(tone, highlight, (t - 0.5f) * 2f)
        }
    }

    /** Gentle S-curve: lights a bit lighter, darks a bit darker, so icons don't all meet in the middle. */
    private fun contrastCurve(t: Float): Float {
        val s = t * t * (3f - 2f * t) // smoothstep
        return t + (s - t) * CONTRAST
    }

    private fun blend(from: Int, to: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        fun channel(a: Int, b: Int) = (a + (b - a) * f).toInt()
        return Color.rgb(
            channel(Color.red(from), Color.red(to)),
            channel(Color.green(from), Color.green(to)),
            channel(Color.blue(from), Color.blue(to))
        )
    }

    /** A fresh, mutable copy - the recolour pass never touches the original (cached) icon bitmap. */
    private fun Drawable.toMutableBitmap(): Bitmap {
        if (this is BitmapDrawable) {
            bitmap?.let { return it.copy(Bitmap.Config.ARGB_8888, true) }
        }
        val width = intrinsicWidth.takeIf { it > 0 } ?: 1
        val height = intrinsicHeight.takeIf { it > 0 } ?: 1
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        setBounds(0, 0, width, height)
        draw(canvas)
        return bmp
    }
}