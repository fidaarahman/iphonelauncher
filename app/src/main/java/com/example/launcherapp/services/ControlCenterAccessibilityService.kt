package com.example.launcherapp.services

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import com.example.launcherapp.R
import com.example.launcherapp.ui.model.ControlCenterSettings
import java.util.Calendar

/**
 * Real Control Center: a thin invisible strip pinned to the top of the screen catches a swipe-down
 * gesture (works over any app - that's the whole point of using an accessibility overlay instead
 * of a normal app window, which could never draw above other apps), then shows the floating panel.
 *
 * Known limitation: the detector strip claims every touch that starts in it while Control Center is
 * on, including ones meant for the real notification shade or for scrolling content pinned to the
 * top of whatever app is open - there's no reliable way to tell those apart from just the first
 * touch-down point. Turning the Control Center toggle off stops it from intercepting anything.
 */
class ControlCenterAccessibilityService : AccessibilityService() {

    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private val cameraId: String? by lazy {
        runCatching {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
    }

    private var edgeDetectorView: View? = null
    private var panelContainer: FrameLayout? = null
    private var flashlightOn = false

    private var nightShiftOverlayView: View? = null
    private var nightShiftActive = false
    private val nightShiftHandler = Handler(Looper.getMainLooper())
    private val nightShiftRunnable = object : Runnable {
        override fun run() {
            applyNightShiftState()
            nightShiftHandler.postDelayed(this, NIGHT_SHIFT_CHECK_INTERVAL_MS)
        }
    }
    private val nightShiftRefreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = applyNightShiftState()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        addEdgeDetector()
        registerAccessibilityButton()
        registerNightShiftRefreshReceiver()
        nightShiftHandler.post(nightShiftRunnable)
    }

    private fun registerNightShiftRefreshReceiver() {
        val filter = IntentFilter(ControlCenterSettings.ACTION_REFRESH_NIGHT_SHIFT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(nightShiftRefreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(nightShiftRefreshReceiver, filter)
        }
    }

    /** Lets tapping this service's own icon in the floating accessibility-button stack open Control Center too - much easier to find than the swipe gesture. */
    private fun registerAccessibilityButton() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        accessibilityButtonController.registerAccessibilityButtonCallback(
            object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) {
                    toast("CC: button clicked")
                    if (!ControlCenterSettings.enabled) {
                        toast("CC: Control Center is OFF in settings")
                        return
                    }
                    if (panelContainer != null) hidePanel() else showPanel()
                }
            }
        )
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        hidePanel()
        edgeDetectorView?.let { runCatching { windowManager.removeView(it) } }
        edgeDetectorView = null
        if (flashlightOn) setFlashlight(false)

        nightShiftHandler.removeCallbacks(nightShiftRunnable)
        runCatching { unregisterReceiver(nightShiftRefreshReceiver) }
        hideNightShiftOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // ---------------------------------------------------------------------------------------
    // Gesture detection
    // ---------------------------------------------------------------------------------------

    private fun addEdgeDetector() {
        val detector = View(this)
        detector.setOnTouchListener(EdgeSwipeListener())
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(EDGE_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            // Sits just BELOW the real status bar instead of overlapping it - the status bar is a
            // privileged system layer that otherwise wins the touch first (it needs that exact zone
            // for pulling down the real notification shade), so an overlay placed directly at y=0
            // never actually receives anything.
            y = statusBarHeightPx()
        }
        runCatching { windowManager.addView(detector, params) }
        edgeDetectorView = detector
    }

    private fun statusBarHeightPx(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else dp(FALLBACK_STATUS_BAR_DP)
    }

    private inner class EdgeSwipeListener : View.OnTouchListener {
        private var startY = 0f
        private var triggered = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (!ControlCenterSettings.enabled) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    triggered = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!triggered && event.rawY - startY > dp(SWIPE_THRESHOLD_DP)) {
                        triggered = true
                        showPanel()
                    }
                    return true
                }
            }
            return true
        }
    }

    // ---------------------------------------------------------------------------------------
    // The panel itself
    // ---------------------------------------------------------------------------------------

    private fun showPanel() {
        if (panelContainer != null) return
        val container = FrameLayout(this)
        container.setOnClickListener { hidePanel() }

        val panelView = LayoutInflater.from(this).inflate(R.layout.view_control_center_panel, container, false)
        panelView.isClickable = true // swallows taps so they don't fall through to the dismiss handler above
        bindPanelControls(panelView)
        applyResizeSettings(panelView)

        container.addView(
            panelView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(PANEL_TOP_MARGIN_DP)
            }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        runCatching { windowManager.addView(container, params) }
        panelContainer = container

        if (ControlCenterSettings.vibrationEnabled) vibrate()
    }

    private fun hidePanel() {
        panelContainer?.let { runCatching { windowManager.removeView(it) } }
        panelContainer = null
    }

    /**
     * Applies the saved Resize sliders to the panel - Width/Height scale it (around its own
     * center, so it grows/shrinks evenly) and Alpha controls its opacity. Scale/alpha are plain
     * View transforms rather than re-measuring the layout, since the panel's content is just a
     * fixed row of tiles - scaling the whole thing is the simplest way to make it bigger/smaller.
     */
    private fun applyResizeSettings(panelView: View) {
        panelView.scaleX = resizeScale(ControlCenterSettings.resizeWidth)
        panelView.scaleY = resizeScale(ControlCenterSettings.resizeHeight)
        panelView.alpha = resizeAlpha(ControlCenterSettings.resizeAlpha)
    }

    private fun resizeScale(progress: Int): Float =
        RESIZE_SCALE_MIN + (progress / 100f) * (RESIZE_SCALE_MAX - RESIZE_SCALE_MIN)

    private fun resizeAlpha(progress: Int): Float =
        RESIZE_ALPHA_MIN + (progress / 100f) * (1f - RESIZE_ALPHA_MIN)

    private fun bindPanelControls(panelView: View) {
        val flashlightTile = panelView.findViewById<View>(R.id.ccFlashlightTile)
        flashlightTile.setOnClickListener { toggleFlashlight(flashlightTile) }

        panelView.findViewById<View>(R.id.ccWifiTile).setOnClickListener {
            // Apps can't toggle WiFi directly since Android 10 - this opens the OS's own quick-toggle panel instead.
            openOutsidePanel(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
        }
        panelView.findViewById<View>(R.id.ccBluetoothTile).setOnClickListener {
            openOutsidePanel(Settings.ACTION_BLUETOOTH_SETTINGS)
        }
        panelView.findViewById<View>(R.id.ccAirplaneTile).setOnClickListener {
            openOutsidePanel(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
        }
    }

    private fun openOutsidePanel(action: String) {
        hidePanel()
        runCatching { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // ---------------------------------------------------------------------------------------
    // Flashlight - the one tile that actually works without leaving the panel
    // ---------------------------------------------------------------------------------------

    private fun toggleFlashlight(tile: View) {
        val id = cameraId ?: return
        flashlightOn = !flashlightOn
        if (!setFlashlight(flashlightOn, id)) {
            flashlightOn = !flashlightOn // revert the visual state if the camera rejected it
            return
        }
        tile.setBackgroundResource(if (flashlightOn) R.drawable.bg_chip_round_orange else R.drawable.bg_chip_round_off)
    }

    private fun setFlashlight(on: Boolean, id: String? = cameraId): Boolean {
        val cameraIdOrNull = id ?: return false
        return runCatching { cameraManager.setTorchMode(cameraIdOrNull, on) }.isSuccess
    }

    // ---------------------------------------------------------------------------------------
    // Night Shift - a warm screen tint, either on a schedule or manually enabled. Runs regardless
    // of the Control Center toggle above - they're independent features that happen to share this
    // one settings screen and this one service (since any overlay needs Accessibility running).
    // ---------------------------------------------------------------------------------------

    private fun applyNightShiftState() {
        val active = ControlCenterSettings.nightShiftManuallyEnabled || isWithinScheduledWindow()
        if (active != nightShiftActive) {
            nightShiftActive = active
            if (ControlCenterSettings.nightShiftVibration) vibrate()
        }
        if (active) showNightShiftOverlay() else hideNightShiftOverlay()
    }

    private fun isWithinScheduledWindow(): Boolean {
        val now = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        val from = ControlCenterSettings.nightShiftFromMinutes
        val to = ControlCenterSettings.nightShiftToMinutes
        // The window can wrap past midnight (e.g. 22:00 -> 07:00), where "from" is later than "to".
        return if (from <= to) now in from until to else now >= from || now < to
    }

    private fun showNightShiftOverlay() {
        val color = nightShiftTintColor(ControlCenterSettings.nightShiftColorTemperature)
        val existing = nightShiftOverlayView
        if (existing != null) {
            existing.setBackgroundColor(color) // already showing - just refresh the tint in place
            return
        }
        val overlay = View(this).apply { setBackgroundColor(color) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        runCatching { windowManager.addView(overlay, params) }
        nightShiftOverlayView = overlay
    }

    private fun hideNightShiftOverlay() {
        nightShiftOverlayView?.let { runCatching { windowManager.removeView(it) } }
        nightShiftOverlayView = null
    }

    /** [temperature] 0 (coolest, barely visible) to 100 (warmest, a noticeable amber tint). */
    private fun nightShiftTintColor(temperature: Int): Int {
        val alpha = (MIN_TINT_ALPHA + (temperature / 100f) * (MAX_TINT_ALPHA - MIN_TINT_ALPHA)).toInt()
        return Color.argb(alpha, 255, 160, 40)
    }

    private fun vibrate() {
        val effect = VibrationEffect.createOneShot(VIBRATE_MS, VibrationEffect.DEFAULT_AMPLITUDE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(effect)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EDGE_HEIGHT_DP = 32
        private const val SWIPE_THRESHOLD_DP = 40
        private const val PANEL_TOP_MARGIN_DP = 60
        private const val VIBRATE_MS = 40L
        private const val FALLBACK_STATUS_BAR_DP = 24
        private const val NIGHT_SHIFT_CHECK_INTERVAL_MS = 30_000L
        private const val MIN_TINT_ALPHA = 15
        private const val MAX_TINT_ALPHA = 100
        // At ControlCenterSettings.DEFAULT_PROGRESS (60), scale lands exactly on 1.0x; alpha lands
        // at 0.72 - comfortably visible without forcing fully opaque as the untouched default.
        private const val RESIZE_SCALE_MIN = 0.7f
        private const val RESIZE_SCALE_MAX = 1.2f
        private const val RESIZE_ALPHA_MIN = 0.3f

        /**
         * Whether the user has turned this service on in Settings > Accessibility. Uses
         * AccessibilityManager's own enabled-service list rather than hand-parsing the raw
         * Settings.Secure string - that string's component-name formatting isn't consistent
         * enough across Android versions/OEMs to match reliably by string comparison.
         */
        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
            return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo?.serviceInfo?.packageName == context.packageName &&
                        it.resolveInfo?.serviceInfo?.name == ControlCenterAccessibilityService::class.java.name }
        }
    }
}
