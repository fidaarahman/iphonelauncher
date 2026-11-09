package com.example.launcherapp.utils

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate


class AppController : Application() {

    override fun onCreate() {
        super.onCreate()
        SessionManager.with(this)

        val theme = SessionManager.getString(SessionManager.KEY_THEME_MODE, SessionManager.THEME_LIGHT)
        AppCompatDelegate.setDefaultNightMode(
            if (theme == SessionManager.THEME_DARK) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        )
        }
}
