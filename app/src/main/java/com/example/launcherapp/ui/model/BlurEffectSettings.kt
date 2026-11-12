package com.example.launcherapp.ui.model

import com.example.launcherapp.utils.SessionManager

object BlurEffectSettings {
    private const val KEY_ENABLED = "blur_effect_enabled"

    var enabled: Boolean
        get() = SessionManager.getBool(KEY_ENABLED, true)
        set(value) = SessionManager.putBool(KEY_ENABLED, value)
}
