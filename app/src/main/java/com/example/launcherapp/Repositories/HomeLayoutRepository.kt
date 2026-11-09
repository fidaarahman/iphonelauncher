package com.example.launcherapp.Repositories

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import androidx.annotation.Keep
import com.example.launcherapp.LauncherAppEntry
import com.example.launcherapp.ui.model.HomeGrid
import com.example.launcherapp.ui.model.HomeModel
import com.example.launcherapp.utils.SessionManager
import com.google.gson.annotations.SerializedName


@Keep
data class SavedHomeLayout(
    @SerializedName("version") val version: Int = 1,
    @SerializedName("pages") val pages: List<List<String>>? = null,
    @SerializedName("dock") val dock: List<String>? = null
)


class HomeLayoutRepository(private val context: Context) {

    private val packageManager = context.packageManager

    fun load(apps: List<LauncherAppEntry>, defaultDockPackages: List<String>): HomeModel {
        val saved = runCatching { SessionManager.getObject<SavedHomeLayout>(KEY_HOME_LAYOUT) }.getOrNull()
        return if (saved == null) firstLaunchLayout(apps, defaultDockPackages) else mergeSaved(saved, apps)
    }

    fun save(model: HomeModel) {
        SessionManager.setObject(
            SavedHomeLayout(
                version = LAYOUT_VERSION,
                pages = model.pages.map { it.toList() },
                dock = model.dock.toList()
            ),
            KEY_HOME_LAYOUT
        )
    }

    private fun mergeSaved(saved: SavedHomeLayout, apps: List<LauncherAppEntry>): HomeModel {
        val installed = apps.associateBy { it.key }
        val savedKeys = (saved.pages.orEmpty().flatten() + saved.dock.orEmpty()).toSet()
        val placed = HashSet<String>()

        // Maps a saved key to an installed one. If an app update renamed its launcher activity,
        // the old key no longer exists - match it by package so the app keeps its spot.
        fun resolve(savedKey: String): String? {
            val key = if (savedKey in installed) {
                savedKey
            } else {
                val packageName = savedKey.substringBefore('/')
                apps.firstOrNull {
                    it.packageName == packageName && it.key !in savedKeys && it.key !in placed
                }?.key
            } ?: return null
            return key.takeIf { placed.add(it) }
        }

        val dockResolved = saved.dock.orEmpty().mapNotNull(::resolve)
        val dock = dockResolved.take(HomeGrid.DOCK_CAPACITY).toMutableList()
        placed.removeAll(dockResolved.drop(HomeGrid.DOCK_CAPACITY).toSet())

        val pages = saved.pages.orEmpty()
            .map { page -> page.mapNotNull(::resolve).toMutableList() }
            .toMutableList()

        val model = HomeModel(pages, dock)
        apps.filter { it.key !in placed }
            .sortedBy { it.label.lowercase() }
            .forEach { model.append(it.key) }
        model.trimEmptyPages()
        return model
    }

    private fun firstLaunchLayout(apps: List<LauncherAppEntry>, defaultDockPackages: List<String>): HomeModel {
        val dock = defaultDockPackages.distinct()
            .mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg }?.key }
            .take(HomeGrid.DOCK_CAPACITY)
            .toMutableList()

        val model = HomeModel(mutableListOf(), dock)
        orderForFirstLaunch(apps.filter { it.key !in dock }).forEach { model.append(it.key) }
        return model
    }

    private fun orderForFirstLaunch(apps: List<LauncherAppEntry>): List<LauncherAppEntry> {
        val usage = usageTimeByPackage()
        if (usage.isNotEmpty()) {
            return apps.sortedWith(
                compareByDescending<LauncherAppEntry> { usage[it.packageName] ?: 0L }
                    .thenBy { it.label.lowercase() }
            )
        }
        val essentials = essentialPackages()
        return apps.sortedWith(
            compareBy<LauncherAppEntry> { essentials.indexOf(it.packageName).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenBy { it.label.lowercase() }
        )
    }

    /** Foreground time per package over the last 14 days. Empty if Usage Access is off. */
    private fun usageTimeByPackage(): Map<String, Long> = runCatching {
        val usageStats = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val end = System.currentTimeMillis()
        usageStats.queryAndAggregateUsageStats(end - USAGE_WINDOW_MS, end)
            .mapValues { it.value.totalTimeInForeground }
            .filterValues { it > 0L }
    }.getOrDefault(emptyMap())

    /** Built-in apps shown first on a fresh install (when there's no usage data). */
    private fun essentialPackages(): List<String> = listOf(
        Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
        category(Intent.CATEGORY_APP_GALLERY),
        category(Intent.CATEGORY_APP_CALENDAR),
        Intent(AlarmClock.ACTION_SHOW_ALARMS),
        category(Intent.CATEGORY_APP_CONTACTS),
        category(Intent.CATEGORY_APP_EMAIL),
        category(Intent.CATEGORY_APP_MAPS),
        category(Intent.CATEGORY_APP_CALCULATOR),
        Intent(Settings.ACTION_SETTINGS)
    ).mapNotNull { packageManager.defaultHandlerPackage(it) } + PLAY_STORE_PACKAGE

    private fun category(category: String) = Intent(Intent.ACTION_MAIN).addCategory(category)

    companion object {
        const val KEY_HOME_LAYOUT = "home_layout"
        private const val LAYOUT_VERSION = 1
        private const val USAGE_WINDOW_MS = 14L * 24 * 60 * 60 * 1000
        private const val PLAY_STORE_PACKAGE = "com.android.vending"
    }
}

/**
 * The user's default app for [intent]. If they never picked one, Android answers with its own
 * "Open with" chooser (package "android") - then the first real handler is used instead.
 */
fun PackageManager.defaultHandlerPackage(intent: Intent): String? {
    val resolved = resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    if (resolved != null && resolved != SYSTEM_PACKAGE) return resolved
    return queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        .firstOrNull { it.activityInfo.packageName != SYSTEM_PACKAGE }
        ?.activityInfo?.packageName
}

private const val SYSTEM_PACKAGE = "android"