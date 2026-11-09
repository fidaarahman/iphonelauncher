package com.example.launcherapp

import android.content.Intent
import android.graphics.drawable.Drawable
import com.example.launcherapp.ui.model.AppCategory

data class LauncherAppEntry(
    val label: String,
    val icon: Drawable,
    val packageName: String,
    val activityName: String,
    val launchIntent: Intent? = null,
    /** When the app was first installed - used for App Library "Recently Added". */
    val installTime: Long = 0L,
    /** Which App Library group this app belongs to. */
    val category: AppCategory = AppCategory.OTHER,
    /** Pre-installed by the phone maker (not updated or installed by the user) - kept out of "Recently Added". */
    val isSystemApp: Boolean = false
) {
    /**
     * Unique, stable ID of this app icon ("package/activity"). Saved positions use it.
     * Package alone isn't enough: one app can have several launcher icons.
     */
    val key: String
        get() = "$packageName/$activityName"
}