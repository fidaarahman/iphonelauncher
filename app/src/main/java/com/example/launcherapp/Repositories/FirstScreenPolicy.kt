package com.example.launcherapp.Repositories

/**
 * Which apps are forced onto / kept off the first home page, where this app's own icon is
 * pinned, and which apps get bundled into the "Google" folder instead of their own icon.
 * Edit these lists to change the rule - e.g. block another store, or stop foldering an app.
 */
object FirstScreenPolicy {

    /**
     * Slot index (0-based, in HomeCellLayout.iconCells() free-cell order) this app's own icon
     * is pinned to on the first page. Index 4 = row 3, column 1 - the first slot below the
     * clock widget's row.
     */
    const val OWN_APP_SLOT_INDEX = 4

    /** Synthetic package/key for the auto-generated "Google" folder's own home-grid icon. */
    const val GOOGLE_FOLDER_PACKAGE = "launcher.folder.google"
    const val GOOGLE_FOLDER_KEY = "$GOOGLE_FOLDER_PACKAGE/"
    const val GOOGLE_FOLDER_LABEL = "Google"

    /** Slot index the Google folder is pinned to. Index 15 = row 5, column 4 (rightmost). */
    const val GOOGLE_FOLDER_SLOT_INDEX = 15

    /** Non-essential apps that must always be placed on the first page, as their own icon. */
    val ALWAYS_ALLOWED = setOf(
        "com.whatsapp",
        "com.facebook.katana",
        "com.android.vending",         // Play Store
        "com.google.android.youtube"   // YouTube
    )

    /** Apps kept off the first page regardless of category - add any package name here. */
    private val ALWAYS_BLOCKED = setOf(
        "com.oneplus.mall",     // OnePlus Store
        "net.oneplus.forums"    // Community
    )

    /**
     * True if [packageName] is a non-essential Google app that belongs inside the Google folder
     * instead of getting its own home-screen icon. Essentials (Calendar, Contacts, Phone...)
     * are exempted by the caller, since those stay standalone regardless of who makes them.
     */
    fun isGoogleFolderMember(packageName: String): Boolean =
        packageName.startsWith("com.google.") && packageName !in ALWAYS_ALLOWED

    /** True if [packageName] must be kept off the first page (it overflows onto a later page). */
    fun isBlocked(packageName: String): Boolean {
        if (packageName in ALWAYS_ALLOWED) return false
        if (packageName in ALWAYS_BLOCKED) return true
        return isStoreApp(packageName)
    }

    /** Known app-store packages that don't contain the word "store" in their package name. */
    private val KNOWN_STORE_PACKAGES = setOf(
        "com.heytap.market", // OPPO/OnePlus/Realme "App Market" (BBK/Heytap)
        "com.oppo.market"
    )

    private fun isStoreApp(packageName: String): Boolean =
        packageName == "com.android.vending" ||
            packageName in KNOWN_STORE_PACKAGES ||
            packageName.contains("store", ignoreCase = true)
}
