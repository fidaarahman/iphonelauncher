package com.example.launcherapp.Repositories

/**
 * Which apps are forced onto / kept off the first home page, and where this app's own icon
 * is pinned. Edit these lists to change the rule - e.g. allow another Google app, or block
 * another store.
 */
object FirstScreenPolicy {

    /**
     * Slot index (0-based, in HomeCellLayout.iconCells() free-cell order) this app's own icon
     * is pinned to on the first page. Index 4 = row 3, column 1 - the first slot below the
     * clock widget's row.
     */
    const val OWN_APP_SLOT_INDEX = 4

    /** Non-essential apps that must always be placed on the first page. */
    val ALWAYS_ALLOWED = setOf(
        "com.whatsapp",
        "com.facebook.katana",
        "com.android.vending" // Play Store
    )

    /** Google apps allowed on the first page despite the blanket Google block below. */
    private val GOOGLE_ALLOWED = setOf(
        "com.google.android.gm",         // Gmail
        "com.google.android.apps.maps",  // Maps
        "com.google.android.apps.photos" // Google Photos
    )

    /** Apps kept off the first page regardless of category - add any package name here. */
    private val ALWAYS_BLOCKED = setOf(
        "com.oneplus.mall",     // OnePlus Store
        "net.oneplus.forums"    // Community
    )

    /** True if [packageName] must be kept off the first page (it overflows onto a later page). */
    fun isBlocked(packageName: String): Boolean {
        if (packageName in ALWAYS_ALLOWED) return false
        if (packageName in ALWAYS_BLOCKED) return true
        if (packageName.startsWith("com.google.") && packageName !in GOOGLE_ALLOWED) return true
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
