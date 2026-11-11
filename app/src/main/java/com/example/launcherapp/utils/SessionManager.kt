package com.example.launcherapp.utils

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken

object SessionManager {


    private val PREF_NAME: String = "Launcherappp.pref"
    lateinit var preferences: SharedPreferences

    fun with(application: Application) {
        preferences = application.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun <T> setObject(`object`: T, key: String) {
        val jsonString = GsonBuilder().create().toJson(`object`)
        preferences.edit().putString(key, jsonString).apply()
    }

    inline fun <reified T> getObject(key: String): T? {
        val value = preferences.getString(key, null)
        val type = object : TypeToken<T>() {}.type
        return GsonBuilder().create().fromJson(value, type)
    }

    fun setString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    fun getString(key: String, defVal: String): String {

        return preferences.getString(key, defVal).toString()
    }

    fun putBool(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }

    fun getBool(key: String, defVal: Boolean): Boolean {
        return preferences.getBoolean(key, defVal)
    }

    fun setInt(key: String, value: Int) {
        preferences.edit().putInt(key, value).apply()
    }

    fun getInt(key: String, defVal: Int = 0): Int {
        return preferences.getInt(key, defVal)
    }

    fun putSets(key: String, value: Set<String>) {
        preferences.edit().putStringSet(key, value).apply()
    }

    fun getSets(key: String): MutableSet<String> {
        return preferences.getStringSet(key, mutableSetOf()) ?: mutableSetOf()
    }

    fun putDouble(key: String, value: Double) {
        preferences.edit().putFloat(key, value.toFloat()).apply()
    }

    fun getDouble(key: String, defaultValue: Double): Double {
        return preferences.getFloat(key, defaultValue.toFloat()).toDouble()
    }

    fun putLong(key: String, value: Long) {
        preferences.edit().putLong(key, value).apply()
    }

    fun getLong(key: String, defaultValue: Long): Long {
        return preferences.getLong(key, defaultValue)
    }

    fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }


    const val KEY_THEME_MODE = "setting_theme_mode"
    const val THEME_LIGHT = "Light"
    const val THEME_DARK = "Dark"

}
