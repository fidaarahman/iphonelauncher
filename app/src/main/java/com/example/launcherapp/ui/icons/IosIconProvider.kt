package com.example.launcherapp.ui.icons

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Replaces the icons of built-in apps with bundled iPhone-style icons.
 *
 * Apps are matched by ROLE, not by package name: "the default camera app gets ios_camera",
 * "the default browser gets ios_safari", etc. Package names differ between Samsung, Vivo,
 * Xiaomi... but these roles work on every phone.
 *
 * Images are looked up by name in res/drawable (ios_phone.png, ios_camera.png, ...).
 * If an image hasn't been added yet, that app simply keeps its own icon, so the project
 * builds and runs before all icons are in place.
 */
class IosIconProvider(private val context: Context) {

    private val packageManager = context.packageManager

    /** packageName -> drawable res id. Built once, on first use (thread-safe). */
    private val overrides: Map<String, Int> by lazy { buildOverrides() }

    fun iconFor(packageName: String): Drawable? =
        overrides[packageName]?.let { ContextCompat.getDrawable(context, it) }

    private fun buildOverrides(): Map<String, Int> {
        val result = HashMap<String, Int>()
        for ((intent, drawableName) in roleIcons()) {
            val resId = drawableId(drawableName)
            if (resId == 0) continue
            val packageName = defaultHandler(intent) ?: continue
            result.putIfAbsent(packageName, resId)
        }
        for ((packageName, drawableName) in PACKAGE_ICONS) {
            val resId = drawableId(drawableName)
            if (resId != 0) result.putIfAbsent(packageName, resId)
        }
        return result
    }

    /** The user's default app for [intent]; if none is chosen, the first real handler. */
    private fun defaultHandler(intent: Intent): String? {
        val resolved = packageManager
            .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        if (resolved != null && resolved != SYSTEM_PACKAGE) return resolved
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { it.activityInfo.packageName != SYSTEM_PACKAGE }
            ?.activityInfo?.packageName
    }

    @SuppressLint("DiscouragedApi") // lookup by name so missing images don't break the build
    private fun drawableId(name: String): Int =
        context.resources.getIdentifier(name, "drawable", context.packageName)

    private fun roleIcons(): List<Pair<Intent, String>> = listOf(
        Intent(Intent.ACTION_DIAL) to "ios_phone",
        category(Intent.CATEGORY_APP_MESSAGING) to "ios_messages",
        category(Intent.CATEGORY_APP_BROWSER) to "ios_safari",
        Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) to "ios_camera",
        category(Intent.CATEGORY_APP_GALLERY) to "ios_photos",
        category(Intent.CATEGORY_APP_CALENDAR) to "ios_calendar",
        category(Intent.CATEGORY_APP_CALCULATOR) to "ios_calculator",
        category(Intent.CATEGORY_APP_CONTACTS) to "ios_contacts",
        category(Intent.CATEGORY_APP_EMAIL) to "ios_mail",
        category(Intent.CATEGORY_APP_MAPS) to "ios_maps",
        category(Intent.CATEGORY_APP_MUSIC) to "ios_music",
        category(CATEGORY_APP_FILES) to "ios_files",
        Intent(Settings.ACTION_SETTINGS) to "ios_settings",
        Intent(AlarmClock.ACTION_SHOW_ALARMS) to "ios_clock"
    )

    private fun category(category: String) = Intent(Intent.ACTION_MAIN).addCategory(category)

    companion object {
        private const val SYSTEM_PACKAGE = "android"

        // Intent.CATEGORY_APP_FILES is API 29+; the string works on every version.
        private const val CATEGORY_APP_FILES = "android.intent.category.APP_FILES"

        /** Apps with no standard role - matched by package name. */
        private val PACKAGE_ICONS = mapOf(
            "com.android.vending" to "ios_app_store",     // Play Store
            "com.google.android.keep" to "ios_notes",     // Google Keep
            "com.google.android.apps.weather" to "ios_weather"
        )
    }
}