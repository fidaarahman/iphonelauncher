package com.example.launcherapp.ui.model

import java.util.Calendar
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin


object SunCalculator {

    data class SunTimes(val sunrise: Calendar, val sunset: Calendar)

    /** Null if the sun never rises/sets that day at this latitude (polar day/night). */
    fun calculate(latitude: Double, longitude: Double, forDate: Calendar = Calendar.getInstance()): SunTimes? {
        val julianDate = toJulianDay(
            forDate.get(Calendar.YEAR),
            forDate.get(Calendar.MONTH) + 1,
            forDate.get(Calendar.DAY_OF_MONTH)
        )
        val jStar = julianDate - 2451545.0 + 0.0008 - longitude / 360.0

        val m = Math.toRadians((357.5291 + 0.98560028 * jStar).mod(360.0))
        val c = 1.9148 * sin(m) + 0.0200 * sin(2 * m) + 0.0003 * sin(3 * m)
        val lambda = Math.toRadians((Math.toDegrees(m) + c + 180.0 + 102.9372).mod(360.0))

        val jTransit = 2451545.0 + jStar + 0.0053 * sin(m) - 0.0069 * sin(2 * lambda)

        val declination = asin(sin(lambda) * sin(Math.toRadians(23.44)))
        val latRad = Math.toRadians(latitude)
        val cosOmega = (sin(Math.toRadians(-0.83)) - sin(latRad) * sin(declination)) / (cos(latRad) * cos(declination))
        if (cosOmega !in -1.0..1.0) return null // sun doesn't rise/set today at this latitude

        val omega = Math.toDegrees(acos(cosOmega))
        val jRise = jTransit - omega / 360.0
        val jSet = jTransit + omega / 360.0

        return SunTimes(julianToCalendar(jRise), julianToCalendar(jSet))
    }

    /**
     * Julian Day Number, referenced at NOON UTC (the Fliegel-Van Flandern formula's native
     * convention, matching the Sunrise Equation's own J2000.0-at-noon epoch). Do NOT shift this
     * to midnight (e.g. "- 0.5") - that introduces a flat 12-hour error into every sunrise/sunset
     * this feeds, which is exactly the bug this comment is here to prevent reintroducing.
     */
    private fun toJulianDay(year: Int, month: Int, day: Int): Double {
        val a = (14 - month) / 12
        val y = year + 4800 - a
        val m = month + 12 * a - 3
        return (day + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045).toDouble()
    }

    private fun julianToCalendar(julianDay: Double): Calendar =
        Calendar.getInstance().apply { timeInMillis = ((julianDay - 2440587.5) * 86400000.0).toLong() }
}
