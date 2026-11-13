package com.example.launcherapp.ui.model

import com.example.launcherapp.utils.SessionManager


object ControlCenterSettings {

    /** Sent app-locally when Night Shift settings are saved, so the already-running service re-checks immediately instead of waiting for its next periodic check. */
    const val ACTION_REFRESH_NIGHT_SHIFT = "com.example.launcherapp.action.REFRESH_NIGHT_SHIFT"

    /** Sent app-locally when Control Center is switched on/off, so the running service shows/hides its top-right trigger line right away. */
    const val ACTION_REFRESH_CONTROL_CENTER = "com.example.launcherapp.action.REFRESH_CONTROL_CENTER"

    private const val KEY_ENABLED = "control_center_enabled"
    private const val KEY_VIBRATION_ENABLED = "control_center_vibration_enabled"

    private const val KEY_RESIZE_WIDTH = "control_center_resize_width"
    private const val KEY_RESIZE_HEIGHT = "control_center_resize_height"
    private const val KEY_RESIZE_ALPHA = "control_center_resize_alpha"

    private const val KEY_NIGHT_SHIFT_VIBRATION = "night_shift_vibration_enabled"
    private const val KEY_NIGHT_SHIFT_MANUAL = "night_shift_manual_enabled"
    private const val KEY_NIGHT_SHIFT_FROM = "night_shift_from_minutes"
    private const val KEY_NIGHT_SHIFT_TO = "night_shift_to_minutes"
    private const val KEY_NIGHT_SHIFT_TEMPERATURE = "night_shift_color_temperature"

    const val DEFAULT_PROGRESS = 60
    const val DEFAULT_FROM_MINUTES = 22 * 60 // 10:00 PM
    const val DEFAULT_TO_MINUTES = 7 * 60 // 7:00 AM
    const val DEFAULT_TEMPERATURE = 50

    var enabled: Boolean
        get() = SessionManager.getBool(KEY_ENABLED, true)
        set(value) = SessionManager.putBool(KEY_ENABLED, value)

    var vibrationEnabled: Boolean
        get() = SessionManager.getBool(KEY_VIBRATION_ENABLED, true)
        set(value) = SessionManager.putBool(KEY_VIBRATION_ENABLED, value)

    var resizeWidth: Int
        get() = SessionManager.getInt(KEY_RESIZE_WIDTH, DEFAULT_PROGRESS)
        set(value) = SessionManager.setInt(KEY_RESIZE_WIDTH, value)

    var resizeHeight: Int
        get() = SessionManager.getInt(KEY_RESIZE_HEIGHT, DEFAULT_PROGRESS)
        set(value) = SessionManager.setInt(KEY_RESIZE_HEIGHT, value)

    var resizeAlpha: Int
        get() = SessionManager.getInt(KEY_RESIZE_ALPHA, DEFAULT_PROGRESS)
        set(value) = SessionManager.setInt(KEY_RESIZE_ALPHA, value)

    var nightShiftVibration: Boolean
        get() = SessionManager.getBool(KEY_NIGHT_SHIFT_VIBRATION, false)
        set(value) = SessionManager.putBool(KEY_NIGHT_SHIFT_VIBRATION, value)

    var nightShiftManuallyEnabled: Boolean
        get() = SessionManager.getBool(KEY_NIGHT_SHIFT_MANUAL, false)
        set(value) = SessionManager.putBool(KEY_NIGHT_SHIFT_MANUAL, value)

    /** Minutes since midnight (0-1439). */
    var nightShiftFromMinutes: Int
        get() = SessionManager.getInt(KEY_NIGHT_SHIFT_FROM, DEFAULT_FROM_MINUTES)
        set(value) = SessionManager.setInt(KEY_NIGHT_SHIFT_FROM, value)

    var nightShiftToMinutes: Int
        get() = SessionManager.getInt(KEY_NIGHT_SHIFT_TO, DEFAULT_TO_MINUTES)
        set(value) = SessionManager.setInt(KEY_NIGHT_SHIFT_TO, value)

    /** 0 = coolest, 100 = warmest. */
    var nightShiftColorTemperature: Int
        get() = SessionManager.getInt(KEY_NIGHT_SHIFT_TEMPERATURE, DEFAULT_TEMPERATURE)
        set(value) = SessionManager.setInt(KEY_NIGHT_SHIFT_TEMPERATURE, value)
}