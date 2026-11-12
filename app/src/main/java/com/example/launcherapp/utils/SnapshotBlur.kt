package com.example.launcherapp.utils

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory

/**
 * "Snapshot" blur for panels that sit over a screen that doesn't move while they're open
 * (the folder / App Library group panel):
 *
 * 1. draws only the given [layers] (e.g. wallpaper + home content - NOT the folder itself) into a
 *    small bitmap (1/8 size, so it's fast);
 * 2. blurs it (3 box-blur passes = smooth, close to a gaussian blur);
 * 3. cuts out exactly the part under the panel and returns it as a rounded background with the
 *    same light glass tint as the dock.
 *
 * Done once when the panel opens - no live capture, so the panel's own icons can never end up
 * blurred into its background, and nothing has to be cut out of the dark scrim.
 */
object SnapshotBlur {

    private const val SCALE = 0.125f        // capture at 1/8 size
    private const val BLUR_RADIUS_PX = 5    // in the small bitmap (~40px on screen)
    private const val BLUR_PASSES = 3
    private const val FALLBACK_COLOR = 0xFF1C1C1E.toInt() // when there's nothing to show behind

    /**
     * @param root the full-screen view everything is measured against.
     * @param panel the view that gets the blurred background (must already be laid out).
     * @param layers views to capture, drawn in this order (hidden ones are skipped).
     * @param hideWhileCapturing views inside [layers] to leave out (e.g. live BlurViews).
     * @param deviceWallpaper the phone's wallpaper, drawn first when the launcher shows it (or null).
     * @return the background drawable, or null if anything went wrong (keep the plain background then).
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
        val small = capture(root, layers, hideWhileCapturing, deviceWallpaper)
        boxBlur(small, BLUR_RADIUS_PX, BLUR_PASSES)
        val crop = cropUnder(small, root, panel)
        val blurred = RoundedBitmapDrawableFactory.create(resources, crop).apply {
            cornerRadius = cornerRadiusPx
            setAntiAlias(true)
        }
        val glass = GradientDrawable().apply {
            setColor(overlayColor)
            cornerRadius = cornerRadiusPx
        }
        LayerDrawable(arrayOf(blurred, glass))
    }.getOrNull()

    private fun capture(root: View, layers: List<View>, hide: List<View>, deviceWallpaper: Drawable?): Bitmap {
        val w = (root.width * SCALE).toInt().coerceAtLeast(1)
        val h = (root.height * SCALE).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(w / root.width.toFloat(), h / root.height.toFloat())
        canvas.drawColor(FALLBACK_COLOR)
        deviceWallpaper?.let { drawCenterCrop(canvas, it, root.width, root.height) }

        val hidden = hide.filter { it.visibility == View.VISIBLE }
        hidden.forEach { it.visibility = View.INVISIBLE }
        try {
            val rootPos = IntArray(2).also { root.getLocationInWindow(it) }
            val pos = IntArray(2)
            layers.filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }.forEach { layer ->
                layer.getLocationInWindow(pos)
                canvas.save()
                canvas.translate((pos[0] - rootPos[0]).toFloat(), (pos[1] - rootPos[1]).toFloat())
                layer.draw(canvas)
                canvas.restore()
            }
        } finally {
            hidden.forEach { it.visibility = View.VISIBLE }
        }
        return bitmap
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

    /** The part of the small bitmap that lies under [panel]. */
    private fun cropUnder(small: Bitmap, root: View, panel: View): Bitmap {
        val rootPos = IntArray(2).also { root.getLocationInWindow(it) }
        val pos = IntArray(2).also { panel.getLocationInWindow(it) }
        val sx = small.width / root.width.toFloat()
        val sy = small.height / root.height.toFloat()
        val left = ((pos[0] - rootPos[0]) * sx).toInt().coerceIn(0, small.width - 1)
        val top = ((pos[1] - rootPos[1]) * sy).toInt().coerceIn(0, small.height - 1)
        val width = (panel.width * sx).toInt().coerceIn(1, small.width - left)
        val height = (panel.height * sy).toInt().coerceIn(1, small.height - top)
        return Bitmap.createBitmap(small, left, top, width, height)
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