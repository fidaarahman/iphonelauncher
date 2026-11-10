package com.example.launcherapp.Repositories

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import androidx.annotation.Keep
import com.example.launcherapp.LauncherAppEntry
import com.example.launcherapp.ui.model.HomeGrid
import com.example.launcherapp.ui.model.HomeModel
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName


@Keep
data class SavedHomeLayout(
    @SerializedName("version") val version: Int = 1,
    @SerializedName("pages") val pages: List<List<String>>? = null,
    @SerializedName("dock") val dock: List<String>? = null
)


class HomeLayoutRepository(private val context: Context) {

    private val packageManager = context.packageManager
    private val gson = GsonBuilder().create()

    // Its own prefs file, excluded from Android Auto Backup (see backup_rules.xml /
    // data_extraction_rules.xml) - so a fresh install always starts from firstLaunchLayout()
    // instead of silently restoring a stale cloud-backed-up arrangement.
    private val layoutPrefs: SharedPreferences =
        context.getSharedPreferences(LAYOUT_PREFS_NAME, Context.MODE_PRIVATE)

    fun load(apps: List<LauncherAppEntry>, defaultDockPackages: List<String>): HomeModel {
        val saved = runCatching {
            layoutPrefs.getString(KEY_HOME_LAYOUT, null)?.let { gson.fromJson(it, SavedHomeLayout::class.java) }
        }.getOrNull()
        return if (saved == null) firstLaunchLayout(apps, defaultDockPackages) else mergeSaved(saved, apps)
    }

    fun save(model: HomeModel) {
        val saved = SavedHomeLayout(
            version = LAYOUT_VERSION,
            pages = model.pages.map { it.toList() },
            dock = model.dock.toList()
        )
        layoutPrefs.edit().putString(KEY_HOME_LAYOUT, gson.toJson(saved)).apply()
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
        // Apps the user removed from the Home Screen stay out (they're still in the App Library);
        // only apps that are genuinely new get added at the end.
        val removed = removedFromHome(installedKeys = installed.keys)
        orderForFirstLaunch(apps.filter { it.key !in placed && it.key !in removed }).forEach { model.append(it.key) }
        model.trimEmptyPages()
        return model
    }

    // -----------------------------------------------------------------------------------------
    // "Remove from Home Screen" (long-press menu): the app leaves the home screen but stays
    // installed and in the App Library. Saved, so the next load doesn't add it back as "new".
    // -----------------------------------------------------------------------------------------

    fun markRemovedFromHome(key: String) {
        val keys = removedKeys().toMutableSet()
        if (keys.add(key)) saveRemovedKeys(keys)
    }

    /** Puts an app back on the list of apps the home screen may show (for a later "Add to Home Screen"). */
    fun unmarkRemovedFromHome(key: String) {
        val keys = removedKeys().toMutableSet()
        if (keys.remove(key)) saveRemovedKeys(keys)
    }

    /**
     * Keys removed from the Home Screen. Keys of apps that are no longer installed are dropped,
     * so an app that's uninstalled and later reinstalled shows up on the home screen again.
     */
    private fun removedFromHome(installedKeys: Set<String>): Set<String> {
        val keys = removedKeys()
        val stillInstalled = keys.filterTo(HashSet()) { it in installedKeys }
        if (stillInstalled.size != keys.size) saveRemovedKeys(stillInstalled)
        return stillInstalled
    }

    private fun removedKeys(): Set<String> =
        layoutPrefs.getStringSet(KEY_REMOVED_FROM_HOME, null)?.toSet() ?: emptySet()

    private fun saveRemovedKeys(keys: Set<String>) {
        // Always write a fresh set: SharedPreferences must not be given back the set it returned.
        layoutPrefs.edit().putStringSet(KEY_REMOVED_FROM_HOME, HashSet(keys)).apply()
    }

    private fun firstLaunchLayout(apps: List<LauncherAppEntry>, defaultDockPackages: List<String>): HomeModel {
        val dock = defaultDockPackages.distinct()
            .mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg }?.key }
            .take(HomeGrid.DOCK_CAPACITY)
            .toMutableList()

        val remaining = apps.filter { it.key !in dock }
        // This app's own icon and the Google folder are pinned to fixed slots on the first page
        // (see FirstScreenPolicy.OWN_APP_SLOT_INDEX / GOOGLE_FOLDER_SLOT_INDEX) so they land at a
        // known spot even before the user has arranged anything.
        val ownApp = remaining.firstOrNull { it.packageName == context.packageName }
        val folderEntry = remaining.firstOrNull { it.packageName == FirstScreenPolicy.GOOGLE_FOLDER_PACKAGE }
        val ordered = orderForFirstLaunch(
            remaining.filterNot { it.packageName == context.packageName || it.packageName == FirstScreenPolicy.GOOGLE_FOLDER_PACKAGE }
        )

        val keys = ordered.map { it.key }.toMutableList()
        listOfNotNull(
            ownApp?.let { FirstScreenPolicy.OWN_APP_SLOT_INDEX to it.key },
            folderEntry?.let { FirstScreenPolicy.GOOGLE_FOLDER_SLOT_INDEX to it.key }
        ).sortedBy { it.first }.forEach { (index, key) -> keys.add(index.coerceAtMost(keys.size), key) }

        val model = HomeModel(mutableListOf(), dock)
        keys.forEach { model.append(it) }
        return model
    }

    /**
     * The App Library's "Suggestions" group: real usage time per app over the last 14 days,
     * highest first. Empty if the user hasn't granted Usage Access - callers should just omit
     * the group in that case rather than show it empty.
     */
    fun suggestedApps(apps: List<LauncherAppEntry>, limit: Int = 8): List<LauncherAppEntry> {
        val usage = usageTimeByPackage()
        if (usage.isEmpty()) return emptyList()
        return apps.sortedByDescending { usage[it.packageName] ?: 0L }
            .filter { (usage[it.packageName] ?: 0L) > 0L }
            .take(limit)
    }

    /**
     * Orders apps for page placement: essentials first, then apps FirstScreenPolicy always
     * allows, then everything else, then apps FirstScreenPolicy blocks from the first page
     * (pushed last so they overflow onto a later page instead of appearing on page 1).
     */
    private fun orderForFirstLaunch(apps: List<LauncherAppEntry>): List<LauncherAppEntry> {
        val essentials = essentialPackages()
        fun tier(entry: LauncherAppEntry) = when {
            entry.packageName in essentials -> 0
            entry.packageName == FirstScreenPolicy.GOOGLE_FOLDER_PACKAGE -> 1
            entry.packageName in FirstScreenPolicy.ALWAYS_ALLOWED -> 1
            FirstScreenPolicy.isBlocked(entry.packageName) -> 3
            else -> 2
        }

        val usage = usageTimeByPackage()
        if (usage.isNotEmpty()) {
            return apps.sortedWith(
                compareBy<LauncherAppEntry> { tier(it) }
                    .thenByDescending { usage[it.packageName] ?: 0L }
                    .thenBy { it.installTime }
            )
        }
        return apps.sortedWith(
            compareBy<LauncherAppEntry> { tier(it) }
                .thenBy { essentials.indexOf(it.packageName).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenBy { it.installTime }
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

    /**
     * Built-in apps shown first on a fresh install (when there's no usage data). Also used by
     * the caller to decide which apps are exempt from being bundled into the Google folder.
     */
    fun essentialPackages(): List<String> = listOf(
        Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
        category(Intent.CATEGORY_APP_GALLERY),
        category(Intent.CATEGORY_APP_CALENDAR),
        Intent(AlarmClock.ACTION_SHOW_ALARMS),
        category(Intent.CATEGORY_APP_CONTACTS),
        category(Intent.CATEGORY_APP_EMAIL),
        category(Intent.CATEGORY_APP_MAPS),
        category(Intent.CATEGORY_APP_CALCULATOR),
        Intent(Settings.ACTION_SETTINGS)
    ).mapNotNull { packageManager.defaultHandlerPackage(it) }

    private fun category(category: String) = Intent(Intent.ACTION_MAIN).addCategory(category)

    companion object {
        const val LAYOUT_PREFS_NAME = "home_layout_prefs"
        private const val KEY_HOME_LAYOUT = "home_layout"
        private const val KEY_REMOVED_FROM_HOME = "removed_from_home"
        private const val LAYOUT_VERSION = 1
        private const val USAGE_WINDOW_MS = 14L * 24 * 60 * 60 * 1000
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