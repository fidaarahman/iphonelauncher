package com.example.launcherapp.ui.model

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import com.example.launcherapp.Repositories.defaultHandlerPackage

/**
 * Sorts an app into an [AppCategory]. First rule that matches wins:
 * 1. Known apps (curated list) - most reliable, so it can correct what apps declare about themselves.
 * 2. System roles - the user's default browser, phone, camera, calculator... (see [refreshRoles]).
 * 3. The category the app declares itself (ApplicationInfo.category, API 26+).
 * 4. Keywords in the package name + label, matched as whole words.
 * 5. Otherwise Other.
 */
object AppCategorizer {

    /** Default-app packages for system roles; rebuilt by [refreshRoles] on each app load. */
    @Volatile
    private var rolePackages: Map<String, AppCategory> = emptyMap()

    /** Call once at the start of every app load, off the main thread. */
    fun refreshRoles(packageManager: PackageManager) {
        val roles = LinkedHashMap<String, AppCategory>()
        fun role(intent: Intent, category: AppCategory) {
            packageManager.defaultHandlerPackage(intent)?.let { roles.putIfAbsent(it, category) }
        }
        role(Intent(Intent.ACTION_DIAL), AppCategory.SOCIAL)
        role(main(Intent.CATEGORY_APP_MESSAGING), AppCategory.SOCIAL)
        role(main(Intent.CATEGORY_APP_CONTACTS), AppCategory.SOCIAL)
        role(main(Intent.CATEGORY_APP_BROWSER), AppCategory.UTILITIES)
        role(main(Intent.CATEGORY_APP_CALCULATOR), AppCategory.UTILITIES)
        role(Intent(Settings.ACTION_SETTINGS), AppCategory.UTILITIES)
        role(Intent(AlarmClock.ACTION_SHOW_ALARMS), AppCategory.UTILITIES)
        role(main(CATEGORY_APP_FILES), AppCategory.UTILITIES)
        role(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), AppCategory.CREATIVITY)
        role(main(Intent.CATEGORY_APP_GALLERY), AppCategory.CREATIVITY)
        role(main(Intent.CATEGORY_APP_EMAIL), AppCategory.PRODUCTIVITY)
        role(main(Intent.CATEGORY_APP_CALENDAR), AppCategory.PRODUCTIVITY)
        role(main(Intent.CATEGORY_APP_MAPS), AppCategory.NEWS_TRAVEL)
        role(main(Intent.CATEGORY_APP_MUSIC), AppCategory.ENTERTAINMENT)
        rolePackages = roles
    }

    fun categorize(packageName: String, label: String, packageManager: PackageManager): AppCategory =
        KNOWN_PACKAGES[packageName]
            ?: rolePackages[packageName]
            ?: fromDeclaredCategory(packageName, packageManager)
            ?: fromKeywords(packageName, label)

    private fun fromDeclaredCategory(packageName: String, packageManager: PackageManager): AppCategory? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val category = runCatching {
            packageManager.getApplicationInfo(packageName, 0).category
        }.getOrDefault(ApplicationInfo.CATEGORY_UNDEFINED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && category == ApplicationInfo.CATEGORY_ACCESSIBILITY) {
            return AppCategory.UTILITIES
        }
        return when (category) {
            ApplicationInfo.CATEGORY_SOCIAL -> AppCategory.SOCIAL
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> AppCategory.PRODUCTIVITY
            ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> AppCategory.ENTERTAINMENT
            ApplicationInfo.CATEGORY_IMAGE -> AppCategory.CREATIVITY
            ApplicationInfo.CATEGORY_GAME -> AppCategory.GAMES
            ApplicationInfo.CATEGORY_NEWS, ApplicationInfo.CATEGORY_MAPS -> AppCategory.NEWS_TRAVEL
            else -> null
        }
    }

    /**
     * Whole-word matching on package name + label ("pay" matches "easy pay" but not "display").
     * "store" is deliberately not a shopping word - it would put the Play Store under Shopping.
     */
    private fun fromKeywords(packageName: String, label: String): AppCategory {
        val words = "$packageName $label".lowercase().split(NON_WORD).filter { it.isNotEmpty() }.toSet()
        for ((category, keywords) in KEYWORDS) {
            if (keywords.any { it in words }) return category
        }
        return AppCategory.OTHER
    }

    private fun main(category: String) = Intent(Intent.ACTION_MAIN).addCategory(category)

    // Intent.CATEGORY_APP_FILES is API 29+; the string works on every version.
    private const val CATEGORY_APP_FILES = "android.intent.category.APP_FILES"
    private val NON_WORD = Regex("[^a-z0-9]+")

    /** Checked in this order. */
    private val KEYWORDS: List<Pair<AppCategory, List<String>>> = listOf(
        AppCategory.FINANCE to listOf("pay", "paisa", "bank", "banking", "wallet", "finance", "money", "cash", "invest", "crypto"),
        AppCategory.GAMES to listOf("game", "games", "puzzle", "pool", "chess", "racing", "ludo", "arcade"),
        AppCategory.SHOPPING_FOOD to listOf("shop", "shopping", "mart", "food", "delivery", "eats", "bazaar", "deals"),
        AppCategory.NEWS_TRAVEL to listOf("news", "map", "maps", "gps", "ride", "taxi", "travel", "booking", "flight", "weather"),
        AppCategory.CREATIVITY to listOf("photo", "photos", "camera", "editor", "collage", "draw"),
        AppCategory.ENTERTAINMENT to listOf("music", "video", "tv", "movie", "movies", "stream", "player"),
        AppCategory.PRODUCTIVITY to listOf("office", "docs", "notes", "mail", "calendar", "pdf", "meet", "ai"),
        AppCategory.UTILITIES to listOf(
            "settings", "file", "files", "tool", "tools", "clean", "cleaner", "vpn", "security", "scanner",
            "calculator", "clock", "launcher", "authenticator", "compass", "flashlight", "recorder", "backup"
        )
    )

    /** Well-known apps - checked first, so they're always in the right group. */
    private val KNOWN_PACKAGES: Map<String, AppCategory> = mapOf(
        // Social
        "com.whatsapp" to AppCategory.SOCIAL,
        "com.whatsapp.w4b" to AppCategory.SOCIAL,
        "com.facebook.katana" to AppCategory.SOCIAL,
        "com.facebook.orca" to AppCategory.SOCIAL,
        "com.instagram.android" to AppCategory.SOCIAL,
        "com.snapchat.android" to AppCategory.SOCIAL,
        "com.linkedin.android" to AppCategory.SOCIAL,
        "com.twitter.android" to AppCategory.SOCIAL,
        "org.telegram.messenger" to AppCategory.SOCIAL,
        "com.tencent.mm" to AppCategory.SOCIAL,
        "com.skype.raider" to AppCategory.SOCIAL,

        // Productivity
        "com.google.android.apps.dynamite" to AppCategory.PRODUCTIVITY, // Google Chat
        "us.zoom.videomeetings" to AppCategory.PRODUCTIVITY,
        "com.google.android.gm" to AppCategory.PRODUCTIVITY,
        "com.google.android.apps.docs" to AppCategory.PRODUCTIVITY,
        "com.google.android.apps.docs.editors.sheets" to AppCategory.PRODUCTIVITY,
        "com.google.android.apps.docs.editors.slides" to AppCategory.PRODUCTIVITY,
        "com.microsoft.office.excel" to AppCategory.PRODUCTIVITY,
        "com.microsoft.office.word" to AppCategory.PRODUCTIVITY,
        "com.microsoft.office.powerpoint" to AppCategory.PRODUCTIVITY,
        "com.microsoft.office.outlook" to AppCategory.PRODUCTIVITY,
        "com.microsoft.skydrive" to AppCategory.PRODUCTIVITY,
        "com.microsoft.teams" to AppCategory.PRODUCTIVITY,
        "com.Slack" to AppCategory.PRODUCTIVITY,
        "com.fiverr.fiverr" to AppCategory.PRODUCTIVITY,
        "com.intsig.camscanner" to AppCategory.PRODUCTIVITY,
        "com.openai.chatgpt" to AppCategory.PRODUCTIVITY,
        "com.anthropic.claude" to AppCategory.PRODUCTIVITY,
        "com.microsoft.copilot" to AppCategory.PRODUCTIVITY,
        "com.deepseek.chat" to AppCategory.PRODUCTIVITY,
        "com.google.android.apps.bard" to AppCategory.PRODUCTIVITY, // Gemini

        // Finance
        "com.techlogix.mobilinkcustomer" to AppCategory.FINANCE, // JazzCash
        "pk.com.telenor.phoenix" to AppCategory.FINANCE,         // easypaisa

        // Entertainment
        "com.google.android.youtube" to AppCategory.ENTERTAINMENT,
        "com.google.android.apps.youtube.music" to AppCategory.ENTERTAINMENT,
        "com.spotify.music" to AppCategory.ENTERTAINMENT,
        "com.netflix.mediaclient" to AppCategory.ENTERTAINMENT,
        "com.zhiliaoapp.musically" to AppCategory.ENTERTAINMENT, // TikTok

        // Creativity
        "com.lemon.lvoverseas" to AppCategory.CREATIVITY, // CapCut
        "com.adobe.lrmobile" to AppCategory.CREATIVITY,
        "com.picsart.studio" to AppCategory.CREATIVITY,
        "com.canva.editor" to AppCategory.CREATIVITY,
        "com.google.android.apps.photos" to AppCategory.CREATIVITY,

        // Shopping & Food
        "com.daraz.android" to AppCategory.SHOPPING_FOOD,
        "com.global.foodpanda.android" to AppCategory.SHOPPING_FOOD,

        // News & Travel
        "com.ubercab" to AppCategory.NEWS_TRAVEL,
        "com.careem.acma" to AppCategory.NEWS_TRAVEL,
        "com.bykea.pk" to AppCategory.NEWS_TRAVEL,
        "sinet.startup.inDriver" to AppCategory.NEWS_TRAVEL,
        "com.google.android.apps.maps" to AppCategory.NEWS_TRAVEL,
        "com.booking" to AppCategory.NEWS_TRAVEL,

        // Utilities
        "com.android.vending" to AppCategory.UTILITIES, // Play Store
        "com.cloudflare.onedotonedotonedotone" to AppCategory.UTILITIES, // 1.1.1.1
        "com.google.android.apps.authenticator2" to AppCategory.UTILITIES,
        "com.google.android.apps.googleassistant" to AppCategory.UTILITIES,
        "com.google.android.googlequicksearchbox" to AppCategory.UTILITIES // Google app
    )
}