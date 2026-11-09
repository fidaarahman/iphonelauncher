package com.example.launcherapp

import android.content.Intent
import android.graphics.drawable.Drawable

data class LauncherAppEntry(
    val label: String,
    val icon: Drawable,
    val packageName: String,
    val activityName: String,
    val launchIntent: Intent? = null,
    /** When the app was first installed - used later for App Library "Recently Added". */
    val installTime: Long = 0L
) {
    /**
     * Unique, stable ID of this app icon ("package/activity"). Saved positions use it.
     * Package alone isn't enough: one app can have several launcher icons.
     */
    val key: String
        get() = "$packageName/$activityName"
}