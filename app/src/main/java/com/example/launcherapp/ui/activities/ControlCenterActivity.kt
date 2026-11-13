package com.example.launcherapp.ui.activities

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.launcherapp.R
import com.example.launcherapp.databinding.ActivityControlCenterBinding
import com.example.launcherapp.databinding.DialogControlCenterTutorialBinding
import com.example.launcherapp.databinding.DialogNightShiftBinding
import com.example.launcherapp.databinding.DialogPermissionRequiredBinding
import com.example.launcherapp.databinding.DialogResizeBinding
import com.example.launcherapp.databinding.DialogTurnOnAccessibilityBinding
import com.example.launcherapp.services.ControlCenterAccessibilityService
import com.example.launcherapp.services.ControlCenterNotificationListenerService
import com.example.launcherapp.ui.model.ControlCenterSettings
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Settings UI + the real permission flow (Accessibility, Notification Listener). The actual
 * swipe-gesture detection and floating panel live in ControlCenterAccessibilityService, which
 * reads the Control Center/Vibration toggles saved here directly. Resize/Night Shift values are
 * saved here too, but nothing applies them to the panel yet.
 */
class ControlCenterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityControlCenterBinding

    /** True while the user is going through the permission flow to turn Control Center ON. */
    private var pendingControlCenterEnable = false

    /** Re-entrant guard - programmatic isChecked changes must not re-trigger the listener. */
    private var suppressControlCenterListener = false

    /** The currently-shown "Permission Requires" sheet, if any - refreshed in onResume() after a trip to system Settings. */
    private var permissionSheetBinding: DialogPermissionRequiredBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityControlCenterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        setUpControlCenterToggle()

        binding.switchVibration.isChecked = ControlCenterSettings.vibrationEnabled
        binding.switchVibration.setOnCheckedChangeListener { _, checked ->
            ControlCenterSettings.vibrationEnabled = checked
        }

        binding.rowResize.setOnClickListener { showResizeSheet() }
        binding.rowNightShift.setOnClickListener { showNightShiftSheet() }
    }

    override fun onResume() {
        super.onResume()
        val accessibilityOn = ControlCenterAccessibilityService.isEnabled(this)

        permissionSheetBinding?.let { sheet ->
            sheet.switchListenNotification.isChecked = ControlCenterNotificationListenerService.isEnabled(this)
            sheet.switchAccessibilityPermission.isChecked = accessibilityOn
        }

        suppressControlCenterListener = true
        binding.switchControlCenter.isChecked = if (pendingControlCenterEnable && accessibilityOn) {
            pendingControlCenterEnable = false
            ControlCenterSettings.enabled = true
            notifyControlCenterChanged()
            // Just came back from turning Accessibility on: show how to open it (like the reference app).
            binding.root.post { showControlCenterTutorial() }
            true
        } else {
            // Keeps the toggle honest if Accessibility got revoked from system Settings directly.
            ControlCenterSettings.enabled && accessibilityOn
        }
        suppressControlCenterListener = false
    }

    // ---------------------------------------------------------------------------------------
    // Control Center toggle + permission flow
    // ---------------------------------------------------------------------------------------

    private fun setUpControlCenterToggle() {
        binding.switchControlCenter.isChecked =
            ControlCenterSettings.enabled && ControlCenterAccessibilityService.isEnabled(this)
        binding.switchControlCenter.setOnCheckedChangeListener { _, checked ->
            if (suppressControlCenterListener) return@setOnCheckedChangeListener
            if (checked && !ControlCenterAccessibilityService.isEnabled(this)) {
                suppressControlCenterListener = true
                binding.switchControlCenter.isChecked = false
                suppressControlCenterListener = false
                pendingControlCenterEnable = true
                showPermissionRequiredSheet()
                return@setOnCheckedChangeListener
            }
            ControlCenterSettings.enabled = checked
            notifyControlCenterChanged()
            if (checked) showControlCenterTutorial()
        }
    }

    /** Tells the running service to show / hide its top-right trigger line right away. */
    private fun notifyControlCenterChanged() {
        sendBroadcast(Intent(ControlCenterSettings.ACTION_REFRESH_CONTROL_CENTER).setPackage(packageName))
    }

    // ---------------------------------------------------------------------------------------
    // "Hold & drag to open Control Center" tutorial
    // ---------------------------------------------------------------------------------------

    /**
     * Full-screen dimmed overlay with an animated arrow + hand pointing at the real trigger line
     * (drawn by the service at the top-right edge), "Hold & drag to open Control Center",
     * "Edge settings" and "Got it" - like the reference app.
     */
    private fun showControlCenterTutorial() {
        if (isFinishing || isDestroyed) return
        val tutorial = DialogControlCenterTutorialBinding.inflate(layoutInflater)
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setContentView(tutorial.root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        }

        // "Hold & drag" in yellow, the rest in white.
        val highlight = getString(R.string.cc_tutorial_highlight)
        tutorial.tutorialTitle.text = SpannableStringBuilder(highlight)
            .apply {
                setSpan(ForegroundColorSpan(TUTORIAL_HIGHLIGHT_COLOR), 0, highlight.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append(" ")
                append(getString(R.string.cc_tutorial_rest))
            }

        // The pointer sits under the middle of the trigger line: the line covers the right half of
        // the top edge, so its middle is at 75% of the screen width.
        tutorial.tutorialPointer.post {
            val screenWidth = tutorial.root.width
            val params = tutorial.tutorialPointer.layoutParams as FrameLayout.LayoutParams
            params.marginStart = (screenWidth * 0.75f - tutorial.tutorialPointer.width / 2f).toInt().coerceAtLeast(0)
            params.topMargin = (TUTORIAL_POINTER_GAP_DP * resources.displayMetrics.density).toInt()
            tutorial.tutorialPointer.layoutParams = params
        }
        val bounce = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TUTORIAL_BOUNCE_MS
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                tutorial.tutorialPointer.translationY = t * resources.displayMetrics.density * TUTORIAL_BOUNCE_DP
                tutorial.tutorialPointer.alpha = if (t < 0.8f) 1f else (1f - t) / 0.2f
            }
        }

        tutorial.tutorialEdgeSettings.setOnClickListener {
            dialog.dismiss()
            // Edge Trigger settings screen comes in the next step.
            Toast.makeText(this, R.string.coming_soon, Toast.LENGTH_SHORT).show()
        }
        tutorial.tutorialGotIt.setOnClickListener { dialog.dismiss() }
        tutorial.root.setOnClickListener { /* swallow taps on the dim background */ }
        tutorial.tutorialPointer.visibility = View.VISIBLE

        dialog.setOnDismissListener { bounce.cancel() }
        dialog.show()
        bounce.start()
    }

    private fun showPermissionRequiredSheet() {
        val sheet = DialogPermissionRequiredBinding.inflate(layoutInflater)
        permissionSheetBinding = sheet
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheet.root)
        dialog.setOnDismissListener { permissionSheetBinding = null }

        sheet.switchListenNotification.isChecked = ControlCenterNotificationListenerService.isEnabled(this)
        sheet.switchAccessibilityPermission.isChecked = ControlCenterAccessibilityService.isEnabled(this)

        sheet.rowListenNotificationPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        sheet.rowAccessibilityPermission.setOnClickListener { showTurnOnAccessibilityDialog() }

        dialog.show()
    }

    private fun showTurnOnAccessibilityDialog() {
        val dialogBinding = DialogTurnOnAccessibilityBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this).setView(dialogBinding.root).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        dialogBinding.accessibilityDialogCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.accessibilityDialogAllow.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        dialog.show()
    }

    // ---------------------------------------------------------------------------------------
    // Resize
    // ---------------------------------------------------------------------------------------

    private fun showResizeSheet() {
        val sheet = DialogResizeBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheet.root)

        sheet.resizeWidthSeekBar.progress = ControlCenterSettings.resizeWidth
        sheet.resizeHeightSeekBar.progress = ControlCenterSettings.resizeHeight
        sheet.resizeAlphaSeekBar.progress = ControlCenterSettings.resizeAlpha

        sheet.resizeApply.setOnClickListener {
            ControlCenterSettings.resizeWidth = sheet.resizeWidthSeekBar.progress
            ControlCenterSettings.resizeHeight = sheet.resizeHeightSeekBar.progress
            ControlCenterSettings.resizeAlpha = sheet.resizeAlphaSeekBar.progress
            dialog.dismiss()
        }

        dialog.show()
    }

    // ---------------------------------------------------------------------------------------
    // Night Shift
    // ---------------------------------------------------------------------------------------

    private fun showNightShiftSheet() {
        val sheet = DialogNightShiftBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheet.root)

        sheet.switchNightShiftVibration.isChecked = ControlCenterSettings.nightShiftVibration
        sheet.switchNightShiftManual.isChecked = ControlCenterSettings.nightShiftManuallyEnabled
        sheet.nightShiftTemperatureSeekBar.progress = ControlCenterSettings.nightShiftColorTemperature

        var fromMinutes = ControlCenterSettings.nightShiftFromMinutes
        var toMinutes = ControlCenterSettings.nightShiftToMinutes

        fun refreshTimeFields() {
            sheet.nightShiftFromTime.text = formatMinutesAsTime(fromMinutes)
            sheet.nightShiftToTime.text = formatMinutesAsTime(toMinutes)
            val manual = sheet.switchNightShiftManual.isChecked
            sheet.nightShiftFromTime.isEnabled = !manual
            sheet.nightShiftToTime.isEnabled = !manual
            sheet.nightShiftFromTime.alpha = if (manual) 0.4f else 1f
            sheet.nightShiftToTime.alpha = if (manual) 0.4f else 1f
        }
        refreshTimeFields()

        sheet.switchNightShiftManual.setOnCheckedChangeListener { _, _ -> refreshTimeFields() }
        sheet.nightShiftFromTime.setOnClickListener {
            showTimePicker(fromMinutes) { picked -> fromMinutes = picked; refreshTimeFields() }
        }
        sheet.nightShiftToTime.setOnClickListener {
            showTimePicker(toMinutes) { picked -> toMinutes = picked; refreshTimeFields() }
        }

        sheet.nightShiftApply.setOnClickListener {
            ControlCenterSettings.nightShiftVibration = sheet.switchNightShiftVibration.isChecked
            ControlCenterSettings.nightShiftManuallyEnabled = sheet.switchNightShiftManual.isChecked
            ControlCenterSettings.nightShiftFromMinutes = fromMinutes
            ControlCenterSettings.nightShiftToMinutes = toMinutes
            ControlCenterSettings.nightShiftColorTemperature = sheet.nightShiftTemperatureSeekBar.progress
            // Tells the already-running service to re-check right away instead of waiting for its next periodic check.
            sendBroadcast(Intent(ControlCenterSettings.ACTION_REFRESH_NIGHT_SHIFT).setPackage(packageName))
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showTimePicker(initialMinutes: Int, onPicked: (Int) -> Unit) {
        val hour = initialMinutes / 60
        val minute = initialMinutes % 60
        TimePickerDialog(this, { _, h, m -> onPicked(h * 60 + m) }, hour, minute, true).show()
    }

    private fun formatMinutesAsTime(minutes: Int): String =
        "%02d:%02d".format(minutes / 60, minutes % 60)

    private companion object {
        val TUTORIAL_HIGHLIGHT_COLOR = Color.parseColor("#FFD60A")
        const val TUTORIAL_POINTER_GAP_DP = 12f
        const val TUTORIAL_BOUNCE_MS = 1400L
        const val TUTORIAL_BOUNCE_DP = 70f
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
    }
}