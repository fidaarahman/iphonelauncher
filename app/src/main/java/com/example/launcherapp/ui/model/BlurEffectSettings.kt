package com.example.launcherapp.ui.model

import com.example.launcherapp.utils.SessionManager

/** General screen's "Blur Effect" toggle: real backdrop blur behind the dock/search pill, instead of the flat frosted-glass tint. */
object BlurEffectSettings {
    private const val KEY_ENABLED = "blur_effect_enabled"

    var enabled: Boolean
        get() = SessionManager.getBool(KEY_ENABLED, false)
        set(value) = SessionManager.putBool(KEY_ENABLED, value)
}
