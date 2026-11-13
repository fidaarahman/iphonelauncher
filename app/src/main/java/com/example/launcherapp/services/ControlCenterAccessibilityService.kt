package com.example.launcherapp.services

import android.accessibilityservice.AccessibilityButtonController
import android.animation.ValueAnimator
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.example.launcherapp.R
import com.example.launcherapp.databinding.DialogControlCenterTutorialBinding
import com.example.launcherapp.ui.model.ControlCenterSettings
import com.example.launcherapp.ui.views.ControlCenterTutorial
import com.example.launcherapp.utils.SnapshotBlur
import java.util.Calendar
import kotlin.concurrent.thread
import kotlin.math.hypot

/**
 * Real Control Center: a visible line on the right half of the very top edge (like the reference
 * app) - hold & drag it down and the panel opens, over any app (that's the whole point of using an
 * accessibility overlay instead of a normal app window, which could never draw above other apps).
 *
 * The panel is a full-screen accessibility overlay (not an activity), like the reference app:
 * a heavily blurred copy of the screen behind it (Android 11+: screenshot + blur; otherwise a
 * solid navy gradient - never see-through) with the Figma tiles on top. The tiles themselves
 * are run by [ControlCenterPanel]. It closes on a tap on empty space, a swipe up, Back, the screen
 * turning off, or another app/window coming to the front.
 *
 * The line takes every touch that starts on it (the right half of the status bar), so Android's
 * notification shade is pulled from the left half. With the Control Center toggle off the line is
 * removed completely, so it never blocks anything then.
 */
class ControlCenterAccessibilityService : AccessibilityService() {

    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The visible trigger line at the top-right edge (hold & drag it down to open the panel). */
    private var edgeTriggerView: View? = null

    // ---- Panel window ----
    private var panelContainer: FrameLayout? = null
    private var panelContent: View? = null
    private var panelBackdrop: View? = null
    private var panel: ControlCenterPanel? = null
    private var panelOpening = false
    private var panelClosing = false
    private var panelShownAt = 0L

    // ---- "Hold & drag" tutorial shown over Settings right after the service is turned on ----
    private var tutorialWindow: View? = null
    private var tutorialAnimator: ValueAnimator? = null
    private var tutorialShownAt = 0L

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
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = hidePanel("screen off", animate = false)
    }
    /** Control Center switched on/off in the app: show/hide the trigger line right away. */
    private val controlCenterRefreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "refresh broadcast - Control Center enabled=${ControlCenterSettings.enabled}")
            refreshEdgeTrigger()
            if (!ControlCenterSettings.enabled) {
                hidePanel("Control Center switched off", animate = false)
                hideTutorialOverlay()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "service connected - Control Center enabled=${ControlCenterSettings.enabled}, sdk=${Build.VERSION.SDK_INT}")
        refreshEdgeTrigger()
        registerAccessibilityButton()
        registerNightShiftRefreshReceiver()
        registerScreenOffReceiver()
        registerControlCenterRefreshReceiver()
        nightShiftHandler.post(nightShiftRunnable)
        // Just turned on from our app's flow: show the tutorial right here, over Settings.
        if (ControlCenterSettings.enabled && ControlCenterSettings.tutorialPending) {
            ControlCenterSettings.tutorialPending = false
            // A short wait, so Android's "Allow full control?" dialog has closed first.
            mainHandler.postDelayed({ showTutorialOverlay() }, TUTORIAL_DELAY_MS)
        }
    }

    // ---------------------------------------------------------------------------------------
    // "Hold & drag" tutorial over Settings
    // ---------------------------------------------------------------------------------------

    /**
     * The same tutorial as in the app (ControlCenterTutorial), but as an accessibility overlay, so it
     * appears over Android's Settings the moment the service is turned on. The trigger line is put
     * back on top of it, so the user can try the gesture right away (opening the panel closes it).
     */
    private fun showTutorialOverlay() {
        if (tutorialWindow != null || !ControlCenterSettings.enabled) return
        val tutorial = DialogControlCenterTutorialBinding.inflate(LayoutInflater.from(this))
        // Focusable wrapper, so Back closes the tutorial too.
        val container = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) hideTutorialOverlay()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        container.addView(tutorial.root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
        }
        val added = runCatching { windowManager.addView(container, params) }
        Log.d(TAG, "tutorial over Settings added=${added.isSuccess}" + (added.exceptionOrNull()?.let { ", error=$it" } ?: ""))
        if (added.isFailure) return

        tutorialWindow = container
        tutorialShownAt = SystemClock.uptimeMillis()
        tutorialAnimator = ControlCenterTutorial.bind(
            tutorial,
            onEdgeSettings = {
                hideTutorialOverlay()
                // Edge Trigger settings screen comes in the next step.
                android.widget.Toast.makeText(this, R.string.coming_soon, android.widget.Toast.LENGTH_SHORT).show()
            },
            onGotIt = { hideTutorialOverlay() }
        )
        container.isFocusableInTouchMode = true
        container.requestFocus()

        // Re-add the line so it sits ABOVE the tutorial and can be tried right away.
        removeEdgeTrigger()
        refreshEdgeTrigger()
    }

    private fun hideTutorialOverlay() {
        tutorialAnimator?.cancel()
        tutorialAnimator = null
        tutorialWindow?.let { runCatching { windowManager.removeView(it) } }
        tutorialWindow = null
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

    private fun registerControlCenterRefreshReceiver() {
        val filter = IntentFilter(ControlCenterSettings.ACTION_REFRESH_CONTROL_CENTER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlCenterRefreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(controlCenterRefreshReceiver, filter)
        }
    }

    private fun registerScreenOffReceiver() {
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenOffReceiver, filter)
        }
    }

    /** Lets tapping this service's own icon in the floating accessibility-button stack open Control Center too - much easier to find than the swipe gesture. */
    private fun registerAccessibilityButton() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        accessibilityButtonController.registerAccessibilityButtonCallback(
            object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) {
                    if (!ControlCenterSettings.enabled) return
                    if (panelContainer != null) hidePanel("accessibility button") else showPanel()
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        hidePanel("service stopped", animate = false)
        hideTutorialOverlay()
        removeEdgeTrigger()
        ControlCenterPanel.turnOffTorchIfOn(this)

        nightShiftHandler.removeCallbacks(nightShiftRunnable)
        runCatching { unregisterReceiver(nightShiftRefreshReceiver) }
        runCatching { unregisterReceiver(screenOffReceiver) }
        runCatching { unregisterReceiver(controlCenterRefreshReceiver) }
        hideNightShiftOverlay()
    }

    /**
     * Another app's SCREEN (a real activity) came to the front - e.g. Home pressed, a notification
     * opened: close the panel. Floating overlays of other apps (other control-center / bubble apps),
     * dialogs and Android's own shade only change focus - they must not close it.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        closeTutorialIfScreenChanged(event)
        if (panelContainer == null || event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val eventPackage = event.packageName?.toString() ?: return
        if (eventPackage == packageName || eventPackage == SYSTEM_UI_PACKAGE) return
        if (SystemClock.uptimeMillis() - panelShownAt < PANEL_EVENT_GRACE_MS) return
        val className = event.className?.toString() ?: return
        if (!isActivity(eventPackage, className)) return
        hidePanel("$eventPackage/$className came to the front")
    }

    /** The user left the Settings screen (Home, back to our app...) without tapping "Got it": close the tutorial. */
    private fun closeTutorialIfScreenChanged(event: AccessibilityEvent?) {
        if (tutorialWindow == null || event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (SystemClock.uptimeMillis() - tutorialShownAt < TUTORIAL_EVENT_GRACE_MS) return
        val eventPackage = event.packageName?.toString() ?: return
        if (eventPackage == SYSTEM_UI_PACKAGE) return
        val className = event.className?.toString() ?: return
        if (isActivity(eventPackage, className)) hideTutorialOverlay()
    }

    private fun isActivity(packageName: String, className: String): Boolean = runCatching {
        packageManager.getActivityInfo(ComponentName(packageName, className), 0)
        true
    }.getOrDefault(false)

    override fun onInterrupt() = Unit

    // ---------------------------------------------------------------------------------------
    // Gesture detection
    // ---------------------------------------------------------------------------------------

    /** Shows the trigger line while Control Center is on, removes it while it's off (so it never blocks touches then). */
    private fun refreshEdgeTrigger() {
        Log.d(TAG, "refreshEdgeTrigger - enabled=${ControlCenterSettings.enabled}, line already shown=${edgeTriggerView != null}")
        if (ControlCenterSettings.enabled) addEdgeTrigger() else removeEdgeTrigger()
    }

    /**
     * The visible line at the very top of the screen, on the right half (like the reference app):
     * hold & drag it down to open the panel. The left half of the top edge still opens Android's
     * own notification shade.
     *
     * It sits ON the status bar: FLAG_LAYOUT_IN_SCREEN + no inset fitting (Android 11+) put the
     * window at the real y=0. On some phones (ColorOS, MIUI...) System UI still takes the drag after
     * ~50-100px to open its own shade - then we close the shade again and show our panel on top
     * (see EdgeSwipeListener's ACTION_CANCEL).
     */
    private fun addEdgeTrigger() {
        if (edgeTriggerView != null) return
        val trigger = EdgeTriggerView(this)
        trigger.setOnTouchListener(EdgeSwipeListener())
        val screenWidth = resources.displayMetrics.widthPixels
        val params = WindowManager.LayoutParams(
            (screenWidth * EDGE_WIDTH_FRACTION).toInt(),
            dp(EDGE_TOUCH_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.RIGHT
            x = 0
            y = 0 // very top of the screen, on the status bar
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0 // don't push the window below the status bar
            }
        }
        val result = runCatching { windowManager.addView(trigger, params) }
        Log.d(
            TAG,
            "addEdgeTrigger - size=${params.width}x${params.height}px, screenWidth=$screenWidth, " +
                    "statusBarHeight=${statusBarHeightPx()}px, added=${result.isSuccess}" +
                    (result.exceptionOrNull()?.let { ", error=$it" } ?: "")
        )
        if (result.isSuccess) {
            edgeTriggerView = trigger
            // Where Android really placed the line (y should be 0 = on top of the status bar).
            trigger.post {
                val location = IntArray(2).also { trigger.getLocationOnScreen(it) }
                Log.d(TAG, "trigger line on screen at x=${location[0]}, y=${location[1]}, size=${trigger.width}x${trigger.height}px")
            }
        }
    }

    private fun removeEdgeTrigger() {
        edgeTriggerView?.let { runCatching { windowManager.removeView(it) } }
        edgeTriggerView = null
    }

    /** Screen rotated / size changed: re-add the line so it's still the right half of the new width. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (edgeTriggerView != null) {
            removeEdgeTrigger()
            refreshEdgeTrigger()
        }
    }

    /**
     * Closes Android's own notification shade when it grabbed our swipe. Android 12+ has a direct
     * action for that (sent right away and again shortly after, because the shade may still be
     * opening the first times); older
     * versions close it with Back.
     */
    private fun dismissSystemShade() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            SHADE_DISMISS_RETRIES_MS.forEach { delay ->
                mainHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) }, delay)
            }
        } else {
            mainHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, SHADE_DISMISS_SECOND_MS)
        }
        Log.d(TAG, "closing Android's notification shade")
    }

    private fun statusBarHeightPx(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else dp(FALLBACK_STATUS_BAR_DP)
    }

    /** Hold the line and drag down: opens the panel once the finger has moved [TRIGGER_DRAG_DP]. */
    private inner class EdgeSwipeListener : View.OnTouchListener {
        private var startY = 0f
        private var triggered = false

        private var moveCount = 0

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (!ControlCenterSettings.enabled) {
                Log.d(TAG, "line touched but Control Center is OFF - ignored")
                return false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    moveCount = 0
                    Log.d(TAG, "line DOWN at rawX=${event.rawX}, rawY=${event.rawY} - opening panel right away")
                    (v as? EdgeTriggerView)?.setPressedLook(true)
                    // Open at once: Android grabs a status-bar drag for its own shade ~70-110ms
                    // after the touch (see the logs), so our panel must already be on screen then.
                    triggered = true
                    showPanel()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    Log.d(TAG, "line UP - dragged ${event.rawY - startY}px after $moveCount moves, opened=$triggered")
                    (v as? EdgeTriggerView)?.setPressedLook(false)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    // System UI took this touch away from us to open its own notification shade
                    // (it does that after ~50-100px of dragging on the status bar). If it was a
                    // downward drag on OUR line, it was meant for us: close Android's shade and
                    // show our panel instead.
                    val dy = event.rawY - startY
                    Log.d(TAG, "line CANCEL - the system took the touch after $moveCount moves (dragged ${dy}px), opened=$triggered")
                    (v as? EdgeTriggerView)?.setPressedLook(false)
                    if (triggered || dy > dp(TRIGGER_DRAG_DP)) {
                        dismissSystemShade()
                        if (!triggered) {
                            triggered = true
                            Log.d(TAG, "opening our panel after closing Android's shade")
                            // A short moment, so the shade has started closing (keeps it out of the blur screenshot).
                            mainHandler.postDelayed({ showPanel() }, SHADE_CLOSE_DELAY_MS)
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    moveCount++
                    if (moveCount <= 3) Log.d(TAG, "line MOVE #$moveCount rawY=${event.rawY} (dy=${event.rawY - startY})")
                    // A short drag is enough - Android takes the touch away after ~50-100px.
                    if (!triggered && event.rawY - startY > dp(TRIGGER_DRAG_DP)) {
                        triggered = true
                        Log.d(TAG, "drag reached ${dp(TRIGGER_DRAG_DP)}px - opening panel")
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

    /** Vibrates right away, takes + blurs a screenshot (Android 11+), then adds the panel window. */
    /**
     * Shows the panel AT ONCE (navy background) - no waiting for the screenshot, so it's on screen
     * before Android can start its own shade. The screenshot is requested first (so it still shows
     * the screen without our panel) and its blur fades in over the navy background when ready.
     */
    private fun showPanel() {
        Log.d(TAG, "showPanel - already open=${panelContainer != null}, opening=$panelOpening")
        if (panelContainer != null || panelOpening) return
        panelOpening = true
        hideTutorialOverlay() // the user just did what the tutorial shows
        if (ControlCenterSettings.vibrationEnabled) vibrate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureBlurredScreen { blurred -> if (blurred != null) fadeInBlur(blurred) }
        }
        addPanelWindow(null)
    }

    /** Cross-fades the panel background from the navy gradient to the blurred screenshot. */
    private fun fadeInBlur(blurred: Drawable) {
        val backdrop = panelBackdrop ?: return
        val current = backdrop.background ?: fallbackBackdrop()
        val transition = TransitionDrawable(arrayOf(current, blurred)).apply { isCrossFadeEnabled = true }
        backdrop.background = transition
        transition.startTransition(BLUR_FADE_MS)
    }

    /** Screenshot of what's on screen right now (before the panel is added), blurred off the main thread. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureBlurredScreen(onReady: (Drawable?) -> Unit) {
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                thread(name = "control-center-blur") {
                    val hardware = runCatching { Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace) }.getOrNull()
                    val software = hardware?.let { runCatching { it.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() }
                    hardware?.recycle()
                    runCatching { result.hardwareBuffer.close() }
                    val blurred = software?.let { bitmap ->
                        SnapshotBlur.regionBackgrounds(
                            resources,
                            BitmapDrawable(resources, bitmap),
                            bitmap.width,
                            bitmap.height,
                            regions = listOf(Rect(0, 0, bitmap.width, bitmap.height)),
                            cornerRadiiPx = listOf(0f),
                            overlayColor = PANEL_BLUR_TINT,
                            blurRadiusPx = PANEL_BLUR_RADIUS_PX
                        )?.firstOrNull()
                    }
                    Log.d(TAG, "screenshot OK (${software?.width}x${software?.height}), blurred=${blurred != null}")
                    mainHandler.post { onReady(blurred) }
                }
            }

            override fun onFailure(errorCode: Int) {
                Log.d(TAG, "screenshot FAILED, errorCode=$errorCode - using the navy background")
                mainHandler.post { onReady(null) }
            }
        }
        val request = runCatching { takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback) }
        if (request.isFailure) {
            Log.d(TAG, "screenshot not allowed: ${request.exceptionOrNull()} - turn the service off/on once in Accessibility settings")
            onReady(null)
        }
    }

    private fun addPanelWindow(blurredScreen: Drawable?) {
        panelOpening = false
        if (panelContainer != null) return

        // Focusable root, so the Back key reaches it and closes the panel.
        val container = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) hidePanel("Back key")
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }

        // Never see-through: the heavily blurred screenshot, or (no screenshot) a solid navy
        // gradient like the Figma background - the screen behind is never readable.
        // Shown at full strength straight away (no fade): it must cover Android's shade at once
        // when the system grabbed the swipe. Only the tiles slide in.
        val backdrop = View(this).apply {
            background = blurredScreen ?: fallbackBackdrop()
        }
        container.addView(backdrop, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val content = LayoutInflater.from(this).inflate(R.layout.view_control_center_panel, container, false)
        content.updatePadding(bottom = dp(PANEL_BOTTOM_GAP_DP))
        // Our own status row (time/signal/Wi-Fi/battery) stands in for the real status bar, which
        // isn't reliably visible above this overlay - sized to match it exactly.
        content.findViewById<View>(R.id.ccStatusBar).updateLayoutParams { height = statusBarHeightPx() }
        container.addView(
            content,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
            }
        )
        applyResizeSettings(content)
        // Resize scales from the top centre, so the panel never slides under the status bar.
        content.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            view.pivotX = view.width / 2f
            view.pivotY = 0f
        }
        // Taps on empty space (anywhere that isn't a tile) and swipes up close the panel.
        container.setOnTouchListener(DismissTouchListener())

        val controller = ControlCenterPanel(this, content) { hidePanel("a tile opened something") }
        controller.start()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val added = runCatching { windowManager.addView(container, params) }
        Log.d(TAG, "panel window added=${added.isSuccess}, blurred background=${blurredScreen != null}" +
                (added.exceptionOrNull()?.let { ", error=$it" } ?: ""))
        if (added.isFailure) {
            controller.stop()
            return
        }

        panelContainer = container
        panelContent = content
        panelBackdrop = backdrop
        panel = controller
        panelShownAt = SystemClock.uptimeMillis()
        container.isFocusableInTouchMode = true
        container.requestFocus()

        // Slide + fade in.
        val targetAlpha = content.alpha // set by applyResizeSettings
        content.alpha = 0f
        content.translationY = -dp(PANEL_SLIDE_DP).toFloat()
        content.animate()
            .alpha(targetAlpha)
            .translationY(0f)
            .setDuration(ANIM_IN_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** Solid Figma-like navy gradient, used when the screenshot can't be taken. */
    private fun fallbackBackdrop(): Drawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(FALLBACK_TOP_COLOR, FALLBACK_MIDDLE_COLOR, FALLBACK_BOTTOM_COLOR)
    )

    /** [reason] is only for the log, so we can see what closed the panel. */
    private fun hidePanel(reason: String, animate: Boolean = true) {
        if (panelContainer == null) return
        if (animate && panelClosing) return // already closing
        Log.d(TAG, "hidePanel - reason: $reason")
        panel?.stop()
        panel = null
        if (!animate) {
            removePanelWindow()
            return
        }
        if (panelClosing) return
        panelClosing = true
        panelContent?.animate()
            ?.alpha(0f)
            ?.translationY(-dp(PANEL_SLIDE_DP) / 2f)
            ?.setDuration(ANIM_OUT_MS)
            ?.setInterpolator(AccelerateInterpolator())
            ?.start()
        val backdrop = panelBackdrop
        if (backdrop == null) {
            removePanelWindow()
        } else {
            backdrop.animate().alpha(0f).setDuration(ANIM_OUT_MS).withEndAction { removePanelWindow() }.start()
        }
    }

    private fun removePanelWindow() {
        panelContainer?.let { runCatching { windowManager.removeView(it) } }
        panelContainer = null
        panelContent = null
        panelBackdrop = null
        panelClosing = false
    }

    /** Tap on empty space, or a swipe up anywhere outside the tiles, closes the panel. */
    private inner class DismissTouchListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    if (downY - event.rawY > dp(SWIPE_THRESHOLD_DP)) hidePanel("swipe up")
                }
                MotionEvent.ACTION_UP -> {
                    if (hypot(event.rawX - downX, event.rawY - downY) < dp(TAP_SLOP_DP)) hidePanel("tap on empty space")
                }
            }
            return true
        }
    }

    /**
     * Applies the saved Resize sliders to the panel - Width/Height scale it (from its top centre)
     * and Alpha controls its opacity. Scale/alpha are plain View transforms rather than
     * re-measuring the layout - scaling the whole thing is the simplest way to make it
     * bigger/smaller.
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

    /**
     * The trigger line: a grey rounded bar drawn at the very top of its (taller, invisible) touch
     * area, so it's easy to grab. Slightly brighter while held.
     */
    private class EdgeTriggerView(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LINE_COLOR }
        private val rect = RectF()

        fun setPressedLook(pressed: Boolean) {
            paint.color = if (pressed) LINE_PRESSED_COLOR else LINE_COLOR
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val thickness = LINE_THICKNESS_DP * density
            val inset = LINE_SIDE_INSET_DP * density
            rect.set(inset, LINE_TOP_DP * density, width - inset, LINE_TOP_DP * density + thickness)
            canvas.drawRoundRect(rect, thickness / 2f, thickness / 2f, paint)
        }

        private companion object {
            const val LINE_COLOR = 0xCCB4B4B4.toInt()
            const val LINE_PRESSED_COLOR = 0xFFE0E0E0.toInt()
            const val LINE_THICKNESS_DP = 5f
            const val LINE_TOP_DP = 1f
            const val LINE_SIDE_INSET_DP = 6f
        }
    }

    companion object {
        /** Logcat tag - filter by "ControlCenter" in Android Studio's Logcat. */
        private const val TAG = "ControlCenter"

        // Trigger line: right half of the top edge; touch area 28dp tall (the visible line is 5dp).
        private const val EDGE_WIDTH_FRACTION = 0.5f
        private const val EDGE_TOUCH_HEIGHT_DP = 28
        // How far the finger must drag down on the line to open the panel. Small on purpose:
        // System UI takes the touch away for its own shade after ~50-100px (see the logs).
        private const val TRIGGER_DRAG_DP = 10
        // Closing Android's shade when it grabbed our swipe anyway.
        private val SHADE_DISMISS_RETRIES_MS = longArrayOf(60L, 150L, 280L, 450L)
        private const val BLUR_FADE_MS = 180
        // Wait after the service is turned on before the tutorial appears over Settings.
        private const val TUTORIAL_DELAY_MS = 500L
        private const val TUTORIAL_EVENT_GRACE_MS = 1000L
        private const val SHADE_DISMISS_SECOND_MS = 350L
        private const val SHADE_CLOSE_DELAY_MS = 120L
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val SWIPE_THRESHOLD_DP = 40
        private const val TAP_SLOP_DP = 10
        private const val VIBRATE_MS = 40L
        private const val FALLBACK_STATUS_BAR_DP = 24
        private const val NIGHT_SHIFT_CHECK_INTERVAL_MS = 30_000L
        private const val MIN_TINT_ALPHA = 15
        private const val MAX_TINT_ALPHA = 100

        // Panel window
        private const val PANEL_BOTTOM_GAP_DP = 24
        private const val PANEL_SLIDE_DP = 40
        private const val ANIM_IN_MS = 240L
        private const val ANIM_OUT_MS = 180L
        // Blur of the screenshot, in its 1/8-size copy (12 = ~100px on screen: nothing readable),
        // and the navy tint on top of it (like the Figma background).
        private const val PANEL_BLUR_RADIUS_PX = 12
        private const val PANEL_BLUR_TINT = 0x8C0B1D3D.toInt()
        // Fallback background (no screenshot possible): solid navy gradient.
        private const val FALLBACK_TOP_COLOR = 0xFF0B1D3D.toInt()
        private const val FALLBACK_MIDDLE_COLOR = 0xFF15325E.toInt()
        private const val FALLBACK_BOTTOM_COLOR = 0xFF1F4A78.toInt()
        // Window changes right after the panel opens belong to the panel itself, not another app.
        private const val PANEL_EVENT_GRACE_MS = 700L

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