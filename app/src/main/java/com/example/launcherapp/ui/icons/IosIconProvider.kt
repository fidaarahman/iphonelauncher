package com.example.launcherapp.ui.icons

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat


class IosIconProvider(private val context: Context) {

    private val packageManager = context.packageManager

    /** packageName -> drawable res id. Built once, on first use (thread-safe). */
    private val overrides: Map<String, Int> by lazy { buildOverrides() }

    /** The device's default calendar app - drawn live instead of from a static image, so the day shown is always today's. */
    private val calendarPackage: String? by lazy { defaultHandler(category(Intent.CATEGORY_APP_CALENDAR)) }

    fun iconFor(packageName: String): Drawable? {
        if (packageName == calendarPackage) return LiveCalendarIcon.draw(context)
        return overrides[packageName]?.let { ContextCompat.getDrawable(context, it) }
    }

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
        Intent(Intent.ACTION_DIAL) to "ic_phone_bottom_contianer",
        category(Intent.CATEGORY_APP_MESSAGING) to "ic_messages",
        // CATEGORY_APP_BROWSER isn't reliably declared by every browser, so match via a plain
        // http VIEW intent instead - every browser must handle that, by definition.
        Intent(Intent.ACTION_VIEW, Uri.parse("http://")) to "ic_compass",
        Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) to "ic_camera",
        category(Intent.CATEGORY_APP_GALLERY) to "ic_photos",
        category(Intent.CATEGORY_APP_EMAIL) to "ic_mail",
        category(Intent.CATEGORY_APP_MAPS) to "ic_maps",
        category(Intent.CATEGORY_APP_MUSIC) to "ic_music",
        Intent(Settings.ACTION_SETTINGS) to "ic_setting",
        Intent(AlarmClock.ACTION_SHOW_ALARMS) to "ic_clock",
        category(Intent.CATEGORY_APP_CONTACTS) to "ic_contacts",
        category(Intent.CATEGORY_APP_CALCULATOR) to "ic_calculator",
        category(Intent.CATEGORY_APP_FILES) to "ic_files"
    )

    private fun category(category: String) = Intent(Intent.ACTION_MAIN).addCategory(category)

    companion object {
        private const val SYSTEM_PACKAGE = "android"

        /** Apps with no standard role - matched by package name. */
        private val PACKAGE_ICONS = mapOf(
            "com.android.vending" to "ic_playstore",  // Play Store
            "com.google.android.keep" to "ic_notes",  // Google Keep
            "com.oneplus.note" to "ic_notes",         // OnePlus Notes
            "com.vivo.notes" to "ic_notes",           // Vivo Notes
            "com.samsung.android.app.notes" to "ic_notes", // Samsung Notes
            "com.miui.notes" to "ic_notes",           // Xiaomi/MIUI Notes
            "com.coloros.note" to "ic_notes",         // Oppo/Realme (ColorOS) Notes
            "com.huawei.notepad" to "ic_notes",       // Huawei Notepad
            "com.google.android.apps.tasks" to "ic_reminders",   // Google Tasks
            "com.samsung.android.app.reminder" to "ic_reminders", // Samsung Reminder
            "com.microsoft.todos" to "ic_reminders",  // Microsoft To Do
            // Calculator: most OEMs don't declare CATEGORY_APP_CALCULATOR, so match by package too.
            "com.google.android.calculator" to "ic_calculator",       // Pixel / AOSP
            "com.android.calculator2" to "ic_calculator",             // Older AOSP/stock
            "com.sec.android.app.popupcalculator" to "ic_calculator", // Samsung
            "com.miui.calculator" to "ic_calculator",                 // Xiaomi/MIUI/Poco
            "com.coloros.calculator" to "ic_calculator",              // Oppo/Realme (ColorOS/RealmeUI)
            "com.oppo.calculator" to "ic_calculator",                 // Older Oppo
            "com.oneplus.calculator" to "ic_calculator",              // OnePlus
            "com.vivo.calculator" to "ic_calculator",                 // Vivo (FunTouch/OriginOS)
            "com.htc.calculator" to "ic_calculator",                  // HTC
            "com.asus.calculator" to "ic_calculator",                 // Asus (ZenUI)
            "com.huawei.calculator" to "ic_calculator",               // Huawei (EMUI)
            "com.hihonor.calculator" to "ic_calculator",              // Honor (MagicOS, split from Huawei)
            "com.lge.calculator" to "ic_calculator",                  // LG
            "com.motorola.calculator2" to "ic_calculator",            // Motorola
            "com.sonymobile.calculator" to "ic_calculator",           // Sony
            "com.zte.calculator" to "ic_calculator",                  // ZTE
            "com.meizu.flyme.calculator" to "ic_calculator"           // Meizu (Flyme)
        )
    }
}