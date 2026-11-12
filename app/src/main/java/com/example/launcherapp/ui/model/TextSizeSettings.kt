package com.example.launcherapp.ui.model

import com.example.launcherapp.utils.SessionManager

/**
 * "Flexible Text Size" (General screen): a launcher-only scale applied to app name captions
 * (home screen, dock... actually the dock has no labels, App Library, folder/group dialogs,
 * Search suggestions) - independent of the system font size setting.
 */
object TextSizeSettings {
    private const val KEY_ENABLED = "flexible_text_size_enabled"
    private const val KEY_PROGRESS = "flexible_text_size_progress"

    const val MIN_PERCENT = 80
    const val MAX_PERCENT = 160

    /** Slider progress (0-100) that lands exactly on 108%, matching the Figma mock. */
    const val DEFAULT_PROGRESS = 35

    var enabled: Boolean
        get() = SessionManager.getBool(KEY_ENABLED, true)
        set(value) = SessionManager.putBool(KEY_ENABLED, value)

    var progress: Int
        get() = SessionManager.getInt(KEY_PROGRESS, DEFAULT_PROGRESS)
        set(value) = SessionManager.setInt(KEY_PROGRESS, value)

    fun percentFor(progress: Int): Int = MIN_PERCENT + progress * (MAX_PERCENT - MIN_PERCENT) / 100

    val percent: Int get() = percentFor(progress)

    /** 1f (no change) while disabled - callers just multiply a base sp size by this. */
    val scale: Float get() = if (enabled) percent / 100f else 1f
}
