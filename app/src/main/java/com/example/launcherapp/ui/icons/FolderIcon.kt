package com.example.launcherapp.ui.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * Composite "folder" icon: a light rounded tile with up to 4 member icons arranged in a 2x2
 * mini-grid, like iOS's folder preview. Feed the result through IconShaper for the same
 * squircle crop every other home icon gets.
 */
object FolderIcon {

    private const val SIZE_DP = 60
    private const val INSET_FRACTION = 0.1f
    private const val GAP_FRACTION = 0.06f

    fun draw(context: Context, memberIcons: List<Drawable>): Drawable {
        val size = (SIZE_DP * context.resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Plain full-bleed square fill (not pre-rounded) - IconShaper applies the squircle crop
        // afterward, same as every other icon; a rounded fill here would leave the corners
        // transparent and trip IconShaper's "doesn't fill the square" white-inset fallback.
        canvas.drawColor(Color.rgb(230, 230, 235))

        val inset = size * INSET_FRACTION
        val gap = size * GAP_FRACTION
        val cell = (size - inset * 2 - gap) / 2f
        val positions = listOf(
            inset to inset,
            inset + cell + gap to inset,
            inset to inset + cell + gap,
            inset + cell + gap to inset + cell + gap
        )
        memberIcons.take(4).forEachIndexed { index, icon ->
            val (left, top) = positions[index]
            icon.setBounds(left.toInt(), top.toInt(), (left + cell).toInt(), (top + cell).toInt())
            icon.draw(canvas)
        }

        return BitmapDrawable(context.resources, bitmap)
    }
}
