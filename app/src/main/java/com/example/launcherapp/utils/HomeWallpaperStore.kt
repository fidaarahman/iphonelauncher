package com.example.launcherapp.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * The launcher's OWN copy of the wallpaper the user picked for the Home Screen.
 *
 * Why: the home screen can't reliably read the phone's wallpaper back (recent Android versions
 * block that for normal apps), and without a record we couldn't tell "user picked a wallpaper"
 * from "no wallpaper picked" - which the theme packs need:
 *   theme + no wallpaper   -> theme gradient
 *   theme + wallpaper      -> this wallpaper, recoloured in the theme colour
 *   no theme + wallpaper   -> this wallpaper as it is
 *
 * Bundled wallpapers are saved by NAME (resource IDs can change between builds); an uploaded
 * photo is saved as a screen-sized JPEG in the app's own storage (still works if the photo is
 * deleted from the gallery).
 */
object HomeWallpaperStore {

    private const val KEY_SOURCE = "home_wallpaper_source" // "res:<drawable name>" or "file"
    private const val KEY_VERSION = "home_wallpaper_version"
    private const val SOURCE_FILE = "file"
    private const val RES_PREFIX = "res:"
    private const val FILE_NAME = "home_wallpaper.jpg"
    private const val JPEG_QUALITY = 92

    fun hasWallpaper(): Boolean = source().isNotEmpty()

    /** Changes every time a new wallpaper is saved, so the home screen knows to reload it. */
    fun signature(): String = "${source()}@${SessionManager.getLong(KEY_VERSION, 0L)}"

    fun saveResource(context: Context, @DrawableRes resId: Int) {
        val name = runCatching { context.resources.getResourceEntryName(resId) }.getOrNull() ?: return
        File(context.filesDir, FILE_NAME).delete()
        commit(RES_PREFIX + name)
    }

    /** Call off the main thread. Returns false if the copy couldn't be written. */
    fun saveBitmap(context: Context, bitmap: Bitmap): Boolean {
        val metrics = context.resources.displayMetrics
        val maxSide = max(metrics.widthPixels, metrics.heightPixels)
        val longest = max(bitmap.width, bitmap.height)
        val scaled = if (longest > maxSide) {
            val scale = maxSide.toFloat() / longest
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else {
            bitmap
        }
        val target = File(context.filesDir, FILE_NAME)
        val temp = File(context.filesDir, "$FILE_NAME.tmp")
        val written = runCatching {
            FileOutputStream(temp).use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        }.getOrDefault(false)
        if (!written || !temp.renameTo(target)) {
            temp.delete()
            return false
        }
        commit(SOURCE_FILE)
        return true
    }

    /**
     * Loads the saved wallpaper, scaled down to about [reqWidth] x [reqHeight] and MUTABLE (so it
     * can be recoloured in place). Call off the main thread. Null if none is saved / it's missing.
     */
    @SuppressLint("DiscouragedApi") // lookup by saved name - resource IDs aren't stable across builds
    fun load(context: Context, reqWidth: Int, reqHeight: Int): Bitmap? = runCatching {
        val source = source()
        when {
            source.startsWith(RES_PREFIX) -> {
                val resId = context.resources.getIdentifier(source.removePrefix(RES_PREFIX), "drawable", context.packageName)
                if (resId == 0) return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeResource(context.resources, resId, bounds)
                BitmapFactory.decodeResource(context.resources, resId, decodeOptions(bounds, reqWidth, reqHeight))
            }
            source == SOURCE_FILE -> {
                val file = File(context.filesDir, FILE_NAME)
                if (!file.exists()) return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                BitmapFactory.decodeFile(file.path, decodeOptions(bounds, reqWidth, reqHeight))
            }
            else -> null
        }
    }.getOrNull()

    private fun decodeOptions(bounds: BitmapFactory.Options, reqWidth: Int, reqHeight: Int) =
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

    /** Largest power of 2 that keeps the image at least as big as the screen. */
    private fun sampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var sample = 1
        if (width <= 0 || height <= 0 || reqWidth <= 0 || reqHeight <= 0) return sample
        while (width / (sample * 2) >= reqWidth && height / (sample * 2) >= reqHeight) sample *= 2
        return sample
    }

    private fun commit(source: String) {
        SessionManager.setString(KEY_SOURCE, source)
        SessionManager.putLong(KEY_VERSION, System.currentTimeMillis())
    }

    private fun source(): String = SessionManager.getString(KEY_SOURCE, "")
}