package com.example.launcherapp.utils

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory

/**
 * "Snapshot" blur - a blurred copy of what's behind a view, made once instead of live:
 *
 * - [panelBackground]: for the folder / App Library group panel. Draws the given views
 *   (wallpaper + home content, never the folder itself), blurs, cuts out the panel's area.
 *   Main thread only (it draws views).
 * - [regionBackgrounds]: for the dock and the search pill. Only the wallpaper is ever behind
 *   them, so it blurs just the wallpaper drawable - safe to run on a background thread.
 *
 * Every result is a rounded background: the blur + the same light glass tint as before.
 */
object SnapshotBlur {

    private const val SCALE = 0.125f        // capture at 1/8 size (fast, and smoother blur)
    private const val BLUR_RADIUS_PX = 5    // in the small bitmap (~40px on screen)
    private const val BLUR_PASSES = 3
    private const val FALLBACK_COLOR = 0xFF1C1C1E.toInt() // when there's nothing to show behind

    /**
     * Folder panel background (main thread).
     * @param root the full-screen view everything is measured against.
     * @param panel the view that gets the background (must already be laid out).
     * @param layers views to capture, drawn in this order (hidden ones are skipped).
     * @param hideWhileCapturing views inside [layers] to leave out.
     * @param deviceWallpaper the phone's wallpaper, drawn first (or null).
     * @return the background, or null if anything went wrong (keep the plain background then).
     */
    fun panelBackground(
        resources: Resources,
        root: View,
        panel: View,
        layers: List<View>,
        hideWhileCapturing: List<View>,
        deviceWallpaper: Drawable?,
        cornerRadiusPx: Float,
        overlayColor: Int
    ): Drawable? = runCatching {
        if (root.width <= 0 || root.height <= 0 || panel.width <= 0 || panel.height <= 0) return null
        val small = newSmallBitmap(root.width, root.height)
        val canvas = scaledCanvas(small, root.width, root.height)
        deviceWallpaper?.let { drawCenterCrop(canvas, it, root.width, root.height) }
        drawViews(canvas, root, layers, hideWhileCapturing)
        boxBlur(small, BLUR_RADIUS_PX, BLUR_PASSES)
        rounded(resources, crop(small, root.width, root.height, rectInRoot(root, panel)), cornerRadiusPx, overlayColor)
    }.getOrNull()

    /**
     * Dock / search pill backgrounds (any thread): blurs [wallpaper] as it's shown full-screen
     * (centerCrop over [screenWidth] x [screenHeight]) and returns one background per region.
     * Pass a COPY of the wallpaper drawable (constantState.newDrawable()), not the one on screen.
     * @return one drawable per region (same order), or null if anything went wrong.
     */
    fun regionBackgrounds(
        resources: Resources,
        wallpaper: Drawable,
        screenWidth: Int,
        screenHeight: Int,
        regions: List<Rect>,
        cornerRadiiPx: List<Float>,
        overlayColor: Int
    ): List<Drawable>? = runCatching {
        if (screenWidth <= 0 || screenHeight <= 0 || regions.isEmpty()) return null
        val small = newSmallBitmap(screenWidth, screenHeight)
        val canvas = scaledCanvas(small, screenWidth, screenHeight)
        drawCenterCrop(canvas, wallpaper, screenWidth, screenHeight)
        boxBlur(small, BLUR_RADIUS_PX, BLUR_PASSES)
        regions.mapIndexed { index, region ->
            rounded(resources, crop(small, screenWidth, screenHeight, region), cornerRadiiPx[index], overlayColor)
        }
    }.getOrNull()

    /** [view]'s position and size inside [root]. */
    fun rectInRoot(root: View, view: View): Rect {
        val rootPos = IntArray(2).also { root.getLocationInWindow(it) }
        val pos = IntArray(2).also { view.getLocationInWindow(it) }
        val left = pos[0] - rootPos[0]
        val top = pos[1] - rootPos[1]
        return Rect(left, top, left + view.width, top + view.height)
    }

    // ---------------------------------------------------------------------------------------

    private fun newSmallBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(
            (width * SCALE).toInt().coerceAtLeast(1),
            (height * SCALE).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )

    /** Canvas on [small] that takes full-size coordinates, starting from the fallback colour. */
    private fun scaledCanvas(small: Bitmap, fullWidth: Int, fullHeight: Int): Canvas =
        Canvas(small).apply {
            scale(small.width / fullWidth.toFloat(), small.height / fullHeight.toFloat())
            drawColor(FALLBACK_COLOR)
        }

    private fun drawViews(canvas: Canvas, root: View, layers: List<View>, hide: List<View>) {
        val hidden = hide.filter { it.visibility == View.VISIBLE }
        hidden.forEach { it.visibility = View.INVISIBLE }
        try {
            layers.filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }.forEach { layer ->
                val rect = rectInRoot(root, layer)
                canvas.save()
                canvas.translate(rect.left.toFloat(), rect.top.toFloat())
                layer.draw(canvas)
                canvas.restore()
            }
        } finally {
            hidden.forEach { it.visibility = View.VISIBLE }
        }
    }

    /** Draws [drawable] like ImageView's centerCrop: fills [w] x [h], keeping its aspect ratio. */
    private fun drawCenterCrop(canvas: Canvas, drawable: Drawable, w: Int, h: Int) {
        val dw = drawable.intrinsicWidth
        val dh = drawable.intrinsicHeight
        if (dw <= 0 || dh <= 0) {
            drawable.setBounds(0, 0, w, h)
        } else {
            val scale = maxOf(w / dw.toFloat(), h / dh.toFloat())
            val sw = (dw * scale).toInt()
            val sh = (dh * scale).toInt()
            val left = (w - sw) / 2
            val top = (h - sh) / 2
            drawable.setBounds(left, top, left + sw, top + sh)
        }
        drawable.draw(canvas)
    }

    /** The part of the small bitmap under [region] (given in full-size coordinates). */
    private fun crop(small: Bitmap, fullWidth: Int, fullHeight: Int, region: Rect): Bitmap {
        val sx = small.width / fullWidth.toFloat()
        val sy = small.height / fullHeight.toFloat()
        val left = (region.left * sx).toInt().coerceIn(0, small.width - 1)
        val top = (region.top * sy).toInt().coerceIn(0, small.height - 1)
        val width = (region.width() * sx).toInt().coerceIn(1, small.width - left)
        val height = (region.height() * sy).toInt().coerceIn(1, small.height - top)
        return Bitmap.createBitmap(small, left, top, width, height)
    }

    /** Blurred bitmap + glass tint, both with rounded corners. */
    private fun rounded(resources: Resources, bitmap: Bitmap, cornerRadiusPx: Float, overlayColor: Int): Drawable {
        val blurred = RoundedBitmapDrawableFactory.create(resources, bitmap).apply {
            cornerRadius = cornerRadiusPx
            setAntiAlias(true)
        }
        val glass = GradientDrawable().apply {
            setColor(overlayColor)
            cornerRadius = cornerRadiusPx
        }
        return LayerDrawable(arrayOf(blurred, glass))
    }

    /** In-place box blur; several passes look like a smooth gaussian blur. */
    private fun boxBlur(bitmap: Bitmap, radius: Int, passes: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        val temp = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(passes) {
            blurPass(pixels, temp, w, h, radius, horizontal = true)
            blurPass(temp, pixels, w, h, radius, horizontal = false)
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    /** One running-sum pass along rows ([horizontal]) or columns; edges are clamped. */
    private fun blurPass(input: IntArray, output: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val window = 2 * r + 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            val step = if (horizontal) 1 else w
            var sa = 0
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = input[base + i.coerceIn(0, length - 1) * step]
                sa += p ushr 24
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
            }
            for (i in 0 until length) {
                output[base + i * step] =
                    ((sa / window) shl 24) or ((sr / window) shl 16) or ((sg / window) shl 8) or (sb / window)
                val out = input[base + (i - r).coerceIn(0, length - 1) * step]
                val inc = input[base + (i + r + 1).coerceIn(0, length - 1) * step]
                sa += (inc ushr 24) - (out ushr 24)
                sr += ((inc shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                sg += ((inc shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                sb += (inc and 0xFF) - (out and 0xFF)
            }
        }
    }
}