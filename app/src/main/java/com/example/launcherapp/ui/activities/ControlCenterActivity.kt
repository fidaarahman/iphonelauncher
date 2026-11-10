package com.example.launcherapp.ui.activities

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.example.launcherapp.databinding.ActivityControlCenterBinding
import com.example.launcherapp.utils.SessionManager

/**
 * Settings-style Control Center screen (Figma: "Swipe down to Open Control Center"). Reached
 * from the Today View widgets page's Control Center tile. Resize/Custom Controls/Night Shift/
 * Setup The Record are placeholder rows for now, same as the unimplemented rows already on the
 * main Settings screen - only the two toggles actually persist anything.
 */
class ControlCenterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityControlCenterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityControlCenterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.switchControlCenter.isChecked = SessionManager.getBool(KEY_CONTROL_CENTER_ENABLED, true)
        binding.switchControlCenter.setOnCheckedChangeListener { _, checked ->
            SessionManager.putBool(KEY_CONTROL_CENTER_ENABLED, checked)
        }

        binding.switchVibration.isChecked = SessionManager.getBool(KEY_VIBRATION_ENABLED, true)
        binding.switchVibration.setOnCheckedChangeListener { _, checked ->
            SessionManager.putBool(KEY_VIBRATION_ENABLED, checked)
        }
    }

    companion object {
        private const val KEY_CONTROL_CENTER_ENABLED = "control_center_enabled"
        private const val KEY_VIBRATION_ENABLED = "control_center_vibration_enabled"
    }
}
