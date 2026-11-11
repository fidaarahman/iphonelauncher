package com.example.launcherapp.ui.activities

import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.launcherapp.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var wasDefaultHome = false

    private val requestHomeRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val isDefault = isAppDefaultHome()
        binding.switchDefaultLauncher.isChecked = isDefault
        if (wasDefaultHome && !isDefault) {
            // Switched away from us: cover the handoff with our own spinner for a moment instead
            // of cutting straight to the new default launcher's cold-start loading screen.
            binding.switchTransitionOverlay.visibility = android.view.View.VISIBLE
            binding.root.postDelayed({ finish() }, SWITCH_TRANSITION_DELAY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        binding.switchDefaultLauncher.isChecked = isAppDefaultHome()
        binding.switchDefaultLauncher.setOnClickListener {
            binding.switchDefaultLauncher.isChecked = isAppDefaultHome()
            requestDefaultHome()
        }
        binding.rowDefaultHomeScreen.setOnClickListener { requestDefaultHome() }
        binding.rowWallpaper.setOnClickListener { startActivity(Intent(this, WallpaperPickerActivity::class.java)) }
        binding.rowThemePacks.setOnClickListener { startActivity(Intent(this, ThemePacksActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        binding.switchDefaultLauncher.isChecked = isAppDefaultHome()
    }

    /** Pushes the scrollable content below the status bar / above the nav bar - enableEdgeToEdge() draws under both by design, so something has to consume the insets. */
    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainScroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
    }

    private fun isAppDefaultHome(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return false
        return resolveInfo.activityInfo.packageName == packageName
    }

    private fun requestDefaultHome() {
        wasDefaultHome = isAppDefaultHome()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                requestHomeRole.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                return
            }
        }
        requestHomeRole.launch(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    companion object {
        private const val SWITCH_TRANSITION_DELAY_MS = 700L
    }
}