package com.example.launcherapp

import android.content.Intent
import android.graphics.drawable.Drawable

data class LauncherAppEntry(
    val label: String,
    val icon: Drawable,
    val packageName: String,
    val activityName: String,
    val launchIntent: Intent? = null
)