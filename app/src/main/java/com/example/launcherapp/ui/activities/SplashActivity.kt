package com.example.launcherapp.ui.activities

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.example.launcherapp.R
import com.example.launcherapp.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarColors()

        rotateDots()

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, SPLASH_DURATION_MS)
    }

    /** Without this the status/nav bars show the theme's default (purple) colorPrimary instead of
     *  matching splashRoot's own light background - a visible seam at the very top/bottom. */
    private fun applySystemBarColors() {
        val background = ContextCompat.getColor(this, R.color.splash_background)
        window.statusBarColor = background
        window.navigationBarColor = background
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    private fun rotateDots() {
        ObjectAnimator.ofFloat(binding.dotsContainer, View.ROTATION, 0f, 360f).apply {
            duration = DOT_ROTATION_DURATION_MS
            interpolator = LinearInterpolator()
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    companion object {
        private const val SPLASH_DURATION_MS = 3200L
        private const val DOT_ROTATION_DURATION_MS = 1400L
    }
}
