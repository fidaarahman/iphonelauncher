package com.example.launcherapp.ui.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Draws the Calendar icon fresh every call, showing today's real weekday and day-of-month
 * (like Apple's Calendar icon) instead of a static baked-in date.
 */
object LiveCalendarIcon {

    private const val SIZE_DP = 60
    private val WEEKDAY_RED = Color.rgb(255, 59, 48)
    private val DAY_GRAY = Color.rgb(30, 30, 30)

    fun draw(context: Context): Drawable {
        val size = (SIZE_DP * context.resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val weekday = SimpleDateFormat("EEE", Locale.getDefault()).format(Date())
        val dayOfMonth = Calendar.getInstance().get(Calendar.DAY_OF_MONTH).toString()

        canvas.drawText(weekday, size / 2f, size * 0.33f, textPaint(WEEKDAY_RED, size * 0.17f))
        canvas.drawText(dayOfMonth, size / 2f, size * 0.74f, textPaint(DAY_GRAY, size * 0.48f))

        return BitmapDrawable(context.resources, bitmap)
    }

    private fun textPaint(color: Int, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
}
