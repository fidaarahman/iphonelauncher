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
import com.example.launcherapp.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestHomeRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        binding.switchDefaultLauncher.isChecked = isAppDefaultHome()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.switchDefaultLauncher.isChecked = isAppDefaultHome()
        binding.switchDefaultLauncher.setOnClickListener {
            binding.switchDefaultLauncher.isChecked = isAppDefaultHome()
            requestDefaultHome()
        }
        binding.rowDefaultHomeScreen.setOnClickListener { requestDefaultHome() }
    }

    override fun onResume() {
        super.onResume()
        binding.switchDefaultLauncher.isChecked = isAppDefaultHome()
    }

    private fun isAppDefaultHome(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return false
        return resolveInfo.activityInfo.packageName == packageName
    }

    private fun requestDefaultHome() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                requestHomeRole.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                return
            }
        }
        requestHomeRole.launch(Intent(Settings.ACTION_HOME_SETTINGS))
    }
}