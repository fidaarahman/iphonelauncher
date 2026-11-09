package com.example.launcherapp.ui.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * Turns any app icon into an iPhone-style icon: drawn full-bleed and cut into the iOS
 * "squircle" (rounded square with smooth, continuous corners) instead of Android's
 * circle / rounded-square masks.
 *
 * - Adaptive icons (most apps): background + foreground layers are drawn edge to edge.
 * - Old-style icons that already fill a square: drawn edge to edge.
 * - Old-style icons with transparent edges (round logos etc.): placed on a white tile,
 *   which is how they look on iOS.
 *
 * The result is a ready-made bitmap at the exact home icon size (home_icon_size), so the
 * layouts no longer need clipToOutline.
 */
class IconShaper(context: Context) {

    private val resources = context.resources
    private val size = (ICON_SIZE_DP * resources.displayMetrics.density).toInt()

    /** Everything OUTSIDE the squircle - cleared after drawing the icon. */
    private val outsideSquircle = squirclePath(size.toFloat()).apply {
        fillType = Path.FillType.INVERSE_WINDING
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    fun shape(icon: Drawable): Drawable {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && icon is AdaptiveIconDrawable) {
            drawAdaptive(canvas, icon)
        } else {
            drawLegacy(canvas, icon)
        }
        canvas.drawPath(outsideSquircle, clearPaint)
        return BitmapDrawable(resources, bitmap)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun drawAdaptive(canvas: Canvas, icon: AdaptiveIconDrawable) {
        // Adaptive layers are 108dp, of which only the middle 72dp is meant to be seen,
        // so draw them 1.5x larger than the tile and centred.
        val layerSize = (size * 1.5f).toInt()
        val offset = (size - layerSize) / 2
        val background = icon.background
        if (background == null) canvas.drawColor(Color.WHITE)
        listOfNotNull(background, icon.foreground).forEach { layer ->
            layer.setBounds(offset, offset, offset + layerSize, offset + layerSize)
            layer.draw(canvas)
        }
    }

    private fun drawLegacy(canvas: Canvas, icon: Drawable) {
        if (fillsSquare(icon)) {
            icon.setBounds(0, 0, size, size)
            icon.draw(canvas)
        } else {
            canvas.drawColor(Color.WHITE)
            val inset = (size * LEGACY_INSET).toInt()
            icon.setBounds(inset, inset, size - inset, size - inset)
            icon.draw(canvas)
        }
    }

    /** True if the icon is opaque near all four corners, i.e. it's already a full square. */
    private fun fillsSquare(icon: Drawable): Boolean {
        val probe = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, size, size)
        icon.draw(Canvas(probe))
        val p = (size * 0.12f).toInt()
        val far = size - 1 - p
        val opaque = listOf(p to p, far to p, p to far, far to far)
            .all { (x, y) -> Color.alpha(probe.getPixel(x, y)) > 200 }
        probe.recycle()
        return opaque
    }

    /** Superellipse |x|^n + |y|^n = 1 - very close to Apple's continuous-corner icon shape. */
    private fun squirclePath(side: Float): Path {
        val r = side / 2f
        val path = Path()
        for (i in 0..POINTS) {
            val t = 2.0 * Math.PI * i / POINTS
            val c = cos(t)
            val s = sin(t)
            val x = (r + r * sign(c) * abs(c).pow(2.0 / EXPONENT)).toFloat()
            val y = (r + r * sign(s) * abs(s).pow(2.0 / EXPONENT)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    companion object {
        private const val ICON_SIZE_DP = 60
        private const val EXPONENT = 5.0
        private const val POINTS = 120
        private const val LEGACY_INSET = 0.14f
    }
}