package com.example.launcherapp.utils

import androidx.annotation.Keep
import com.example.launcherapp.LauncherAppEntry
import com.google.gson.annotations.SerializedName
import kotlin.math.pow

/**
 * Counts app launches made from the launcher (home, dock, App Library), saved in SessionManager.
 * Feeds App Library "Suggestions" when Usage Access is off.
 *
 * Each app has a score that halves every 7 days without use, so apps used a lot recently rank
 * above apps used a lot months ago.
 */
object AppLaunchTracker {

    @Keep
    data class LaunchStat(
        @SerializedName("score") val score: Double = 0.0,
        @SerializedName("last") val lastLaunch: Long = 0L
    )

    private const val KEY_LAUNCH_STATS = "app_launch_stats"
    private const val HALF_LIFE_MS = 7.0 * 24 * 60 * 60 * 1000

    @Synchronized
    fun recordLaunch(appKey: String, now: Long = System.currentTimeMillis()) {
        val stats = HashMap(load())
        stats[appKey] = LaunchStat(score = decayedScore(stats[appKey], now) + 1.0, lastLaunch = now)
        SessionManager.setObject(stats, KEY_LAUNCH_STATS)
    }

    /** Apps launched from the launcher, highest recent score first. */
    fun mostLaunched(
        apps: List<LauncherAppEntry>,
        limit: Int,
        now: Long = System.currentTimeMillis()
    ): List<LauncherAppEntry> {
        val stats = load()
        if (stats.isEmpty()) return emptyList()
        return apps
            .filter { stats.containsKey(it.key) }
            .sortedByDescending { decayedScore(stats[it.key], now) }
            .take(limit)
    }

    private fun decayedScore(stat: LaunchStat?, now: Long): Double {
        if (stat == null) return 0.0
        val elapsed = (now - stat.lastLaunch).coerceAtLeast(0L).toDouble()
        return stat.score * 0.5.pow(elapsed / HALF_LIFE_MS)
    }

    @Synchronized
    private fun load(): Map<String, LaunchStat> =
        runCatching { SessionManager.getObject<HashMap<String, LaunchStat>>(KEY_LAUNCH_STATS) }
            .getOrNull()
            ?: emptyMap()
}