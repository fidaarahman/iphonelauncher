package com.example.launcherapp.ui.model

import com.example.launcherapp.LauncherAppEntry

/** One tile in the App Library grid: a title and the apps shown inside it. */
data class AppLibraryGroup(val title: String, val apps: List<LauncherAppEntry>)

/**
 * Builds the App Library's groups, in Figma order:
 * Suggestions (always shown when the list isn't empty - the activity falls back to launch counts
 * and default apps when Usage Access is off), Recently Added (apps the user installed in the last
 * 30 days, no pre-installed system apps), then every non-empty [AppCategory] in enum order.
 */
object AppLibraryBuilder {

    private const val GROUP_SIZE = 8
    private const val RECENT_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

    fun build(
        apps: List<LauncherAppEntry>,
        suggested: List<LauncherAppEntry>,
        now: Long = System.currentTimeMillis()
    ): List<AppLibraryGroup> {
        val groups = mutableListOf<AppLibraryGroup>()

        if (suggested.isNotEmpty()) {
            groups += AppLibraryGroup("Suggestions", suggested.take(GROUP_SIZE))
        }

        val recentlyAdded = apps
            .filter { !it.isSystemApp && it.installTime > 0L && now - it.installTime <= RECENT_WINDOW_MS }
            .sortedByDescending { it.installTime }
            .take(GROUP_SIZE)
        if (recentlyAdded.isNotEmpty()) {
            groups += AppLibraryGroup("Recently Added", recentlyAdded)
        }

        val byCategory = apps.groupBy { it.category }
        for (category in AppCategory.entries) {
            val inCategory = byCategory[category].orEmpty().sortedBy { it.label.lowercase() }
            if (inCategory.isNotEmpty()) {
                groups += AppLibraryGroup(category.displayName, inCategory)
            }
        }

        return groups
    }
}