package com.example.launcherapp.ui.model

/**
 * One of the filter chips on the Theme Packs screen. [ALL] isn't a real color family - no
 * [ThemePack] is ever tagged with it - it's a meta-filter meaning "don't filter, show every pack".
 */
enum class ThemeChip(val label: String, val swatch: Int) {
    ALL("All", 0xFF34C759.toInt()),
    GREEN("Green", 0xFF3FA34D.toInt()),
    RED("Red", 0xFFE2672A.toInt()),
    BLUE("Blue", 0xFF4FA3D1.toInt()),
    PURPLE("Purple", 0xFF5B4FD1.toInt()),
    DARK("Dark", 0xFF2A2A2A.toInt()),
    LIGHT("Light", 0xFFC7C7CC.toInt())
}

/**
 * One selectable theme pack: a diagonal background gradient plus a dock accent color. Applying a
 * pack swaps the home screen's background for [gradientStart]->[gradientEnd] and tints the dock;
 * real app icons are left untouched (see the Theme Packs design discussion this was built from).
 */
data class ThemePack(
    val id: String,
    val chip: ThemeChip,
    val gradientStart: Int,
    val gradientEnd: Int,
    val dockAccent: Int
)

/**
 * Colors here are read off the Figma "Theme Packs" screen screenshot by eye, not pixel-sampled -
 * the Figma MCP tool call budget ran out (Starter plan rate limit) before exact fills could be
 * pulled. Close, not exact - adjust freely once exact values are available.
 */
object ThemePacks {
    const val PREF_KEY = "selected_theme_pack_id"

    val all: List<ThemePack> = listOf(
        ThemePack("forest_green", ThemeChip.GREEN, 0xFF3FA34D.toInt(), 0xFF1B5E20.toInt(), 0xFF43A047.toInt()),
        ThemePack("sunset_orange", ThemeChip.RED, 0xFFE2672A.toInt(), 0xFFB23A1E.toInt(), 0xFFE64A19.toInt()),
        ThemePack("dusty_rose", ThemeChip.RED, 0xFFD48EA0.toInt(), 0xFF8C4A5C.toInt(), 0xFFC2185B.toInt()),
        ThemePack("sky_blue", ThemeChip.BLUE, 0xFF4FA3D1.toInt(), 0xFF2C5F8A.toInt(), 0xFF1976D2.toInt()),
        ThemePack("lime", ThemeChip.GREEN, 0xFFA8D044.toInt(), 0xFF6B8E23.toInt(), 0xFF8BC34A.toInt()),
        ThemePack("indigo", ThemeChip.PURPLE, 0xFF5B4FD1.toInt(), 0xFF2C2A6B.toInt(), 0xFF512DA8.toInt()),
        ThemePack("terracotta", ThemeChip.RED, 0xFFC1733F.toInt(), 0xFF7A3F20.toInt(), 0xFFD84315.toInt()),
        ThemePack("teal", ThemeChip.BLUE, 0xFF2F8EB8.toInt(), 0xFF145C73.toInt(), 0xFF00838F.toInt()),
        ThemePack("midnight", ThemeChip.DARK, 0xFF1B1D1B.toInt(), 0xFF2A3629.toInt(), 0xFF37474F.toInt()),
        ThemePack("olive", ThemeChip.DARK, 0xFF4A5A3C.toInt(), 0xFF26311F.toInt(), 0xFF558B2F.toInt()),
        ThemePack("lavender_gray", ThemeChip.PURPLE, 0xFF8FA5A4.toInt(), 0xFF5C6B7A.toInt(), 0xFF7E57C2.toInt()),
        ThemePack("magenta", ThemeChip.PURPLE, 0xFFA65FB9.toInt(), 0xFF6A2C78.toInt(), 0xFF8E24AA.toInt()),
        ThemePack("gold", ThemeChip.LIGHT, 0xFFBF9F2F.toInt(), 0xFF7A661C.toInt(), 0xFFF9A825.toInt()),
        ThemePack("navy", ThemeChip.BLUE, 0xFF1D347E.toInt(), 0xFF0E1A42.toInt(), 0xFF1A237E.toInt())
    )

    fun find(id: String?): ThemePack? = all.firstOrNull { it.id == id }
}
