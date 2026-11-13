package com.example.launcherapp.services

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.launcherapp.R
import com.example.launcherapp.ui.activities.ThemePacksActivity
import com.example.launcherapp.views.VerticalSliderView
import java.util.Date
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Runs the Control Center panel (view_control_center_panel.xml) that
 * ControlCenterAccessibilityService shows over other apps: the real state of every tile, the
 * media player, the brightness / volume sliders, and the permission each tile needs (asked the
 * first time it's used, like the reference app).
 *
 * [start] when the panel is shown, [stop] when it's removed. [close] asks the service to hide
 * the panel (used before opening a settings screen / app).
 *
 * Android limits: apps can't switch Airplane mode, Wi-Fi (Android 10+), mobile data, hotspot or
 * Bluetooth (Android 12+) themselves - those tiles show the real state and open the system panel
 * or settings screen instead.
 */
class ControlCenterPanel(
    private val context: Context,
    private val root: View,
    private val close: () -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private val resolver = context.contentResolver
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listenerComponent = ComponentName(context, ControlCenterNotificationListenerService::class.java)
    private val torchId: String? by lazy { torchCameraId(cameraManager) }

    // ---- Views --------------------------------------------------------------------------------
    private val statusClock: TextView = root.findViewById(R.id.ccStatusClock)
    private val statusWifiIcon: ImageView = root.findViewById(R.id.ccStatusWifi)
    private val statusBatteryFill: View = root.findViewById(R.id.ccStatusBatteryFill)

    private val airplaneTile: View = root.findViewById(R.id.ccAirplaneTile)
    private val airplaneIcon: ImageView = root.findViewById(R.id.ccAirplaneIcon)
    private val airDropTile: View = root.findViewById(R.id.ccAirDropTile)
    private val airDropIcon: ImageView = root.findViewById(R.id.ccAirDropIcon)
    private val wifiTile: View = root.findViewById(R.id.ccWifiTile)
    private val wifiIcon: ImageView = root.findViewById(R.id.ccWifiIcon)
    private val cellularTile: View = root.findViewById(R.id.ccCellularTile)
    private val cellularIcon: ImageView = root.findViewById(R.id.ccCellularIcon)
    private val bluetoothTile: View = root.findViewById(R.id.ccBluetoothTile)
    private val bluetoothIcon: ImageView = root.findViewById(R.id.ccBluetoothIcon)
    private val hotspotTile: View = root.findViewById(R.id.ccHotspotTile)
    private val hotspotIcon: ImageView = root.findViewById(R.id.ccHotspotIcon)
    private val vpnTile: View = root.findViewById(R.id.ccVpnTile)
    private val vpnIcon: ImageView = root.findViewById(R.id.ccVpnIcon)

    private val mediaTile: View = root.findViewById(R.id.ccMediaTile)
    private val mediaArtwork: ImageView = root.findViewById(R.id.ccMediaArtwork)
    private val mediaTitle: TextView = root.findViewById(R.id.ccMediaTitle)
    private val mediaArtist: TextView = root.findViewById(R.id.ccMediaArtist)
    private val mediaPrevious: ImageView = root.findViewById(R.id.ccMediaPrevious)
    private val mediaPlay: ImageView = root.findViewById(R.id.ccMediaPlay)
    private val mediaNext: ImageView = root.findViewById(R.id.ccMediaNext)
    private val airPlayButton: View = root.findViewById(R.id.ccAirPlayButton)

    private val cameraTile: View = root.findViewById(R.id.ccCameraTile)
    private val silentTile: View = root.findViewById(R.id.ccSilentTile)
    private val silentIcon: ImageView = root.findViewById(R.id.ccSilentIcon)
    private val focusTile: View = root.findViewById(R.id.ccFocusTile)
    private val focusBadge: View = root.findViewById(R.id.ccFocusBadge)
    private val focusDetail: TextView = root.findViewById(R.id.ccFocusDetail)
    private val brightnessSlider: VerticalSliderView = root.findViewById(R.id.ccBrightnessSlider)
    private val volumeSlider: VerticalSliderView = root.findViewById(R.id.ccVolumeSlider)

    private val flashlightTile: View = root.findViewById(R.id.ccFlashlightTile)
    private val flashlightBadge: View = root.findViewById(R.id.ccFlashlightBadge)
    private val flashlightIcon: ImageView = root.findViewById(R.id.ccFlashlightIcon)
    private val flashlightDetail: TextView = root.findViewById(R.id.ccFlashlightDetail)
    private val rotationTile: View = root.findViewById(R.id.ccRotationTile)
    private val rotationIcon: ImageView = root.findViewById(R.id.ccRotationIcon)
    private val themeTile: View = root.findViewById(R.id.ccThemeTile)
    private val mirroringTile: View = root.findViewById(R.id.ccMirroringTile)
    private val batteryTile: View = root.findViewById(R.id.ccBatteryTile)
    private val batteryIcon: ImageView = root.findViewById(R.id.ccBatteryIcon)

    // ---- Media session -----------------------------------------------------------------------
    private var controller: MediaController? = null
    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = updateMedia()
        override fun onMetadataChanged(metadata: MediaMetadata?) = updateMedia()
        override fun onSessionDestroyed() = pickMediaController()
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pickMediaController() }

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId != torchId) return
            torchOn = enabled
            renderFlashlight()
        }
    }

    private val refreshRunnable = Runnable { refreshStates() }

    // ---- Status row (time, signal, Wi-Fi, battery) -------------------------------------------
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, CLOCK_UPDATE_MS)
        }
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = renderStatusBattery(intent)
    }

    // -------------------------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------------------------

    fun start() {
        statusBatteryFill.pivotX = 0f
        handler.post(clockRunnable)
        // ACTION_BATTERY_CHANGED is sticky - registerReceiver() hands back the current state
        // immediately as its return value, but onReceive() only fires a moment later. Render that
        // return value right away so the real level shows from the first frame, not a flash of full.
        val stickyBattery = runCatching {
            context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        stickyBattery?.let { renderStatusBattery(it) }
        bindConnectivity()
        bindMedia()
        bindControls()
        setUpSliders()
        refreshStates()
        runCatching { cameraManager.registerTorchCallback(torchCallback, handler) }
        // Throws if the notification-listener permission isn't granted - the tile then shows a hint.
        runCatching { sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler) }
        pickMediaController()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        runCatching { context.unregisterReceiver(batteryReceiver) }
        runCatching { cameraManager.unregisterTorchCallback(torchCallback) }
        runCatching { sessionManager.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    private fun updateClock() {
        statusClock.text = DateFormat.getTimeFormat(context).format(Date())
    }

    private fun renderStatusBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        statusBatteryFill.scaleX = (level / scale.toFloat()).coerceIn(0f, 1f)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        statusBatteryFill.setBackgroundColor(if (charging) STATUS_BATTERY_CHARGING_COLOR else Color.WHITE)
    }

    private fun refreshStates() {
        statusWifiIcon.alpha = if (wifiOn()) 1f else STATUS_WIFI_OFF_ALPHA
        renderRound(airplaneTile, airplaneIcon, airplaneOn(), R.drawable.bg_cc_circle_orange)
        renderRound(airDropTile, airDropIcon, false)
        renderRound(wifiTile, wifiIcon, wifiOn())
        renderRound(cellularTile, cellularIcon, mobileDataOn())
        renderRound(bluetoothTile, bluetoothIcon, bluetoothOn())
        renderRound(hotspotTile, hotspotIcon, false)
        renderRound(vpnTile, vpnIcon, vpnOn())
        renderRound(silentTile, silentIcon, silentOn(), R.drawable.bg_cc_circle_white, SILENT_ON_ICON)
        renderRound(rotationTile, rotationIcon, rotationLocked(), R.drawable.bg_cc_circle_white, ROTATION_ON_ICON)
        batteryIcon.imageTintList = ColorStateList.valueOf(if (power.isPowerSaveMode) BATTERY_SAVER_ICON else Color.WHITE)
        renderFocus()
        renderFlashlight()
        if (!brightnessSlider.isTracking) brightnessSlider.value = currentBrightness()
        if (!volumeSlider.isTracking) volumeSlider.value = currentVolume()
    }

    private fun refreshSoon(delayMs: Long = REFRESH_DELAY_MS) {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, delayMs)
    }

    // -------------------------------------------------------------------------------------------
    // Connectivity
    // -------------------------------------------------------------------------------------------

    private fun bindConnectivity() {
        airplaneTile.setOnClickListener { open(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) }

        // No public "AirDrop" (Quick Share) screen to open - the wireless settings are the closest.
        airDropTile.setOnClickListener { open(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }

        wifiTile.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                open(Intent(Settings.Panel.ACTION_WIFI))
            } else {
                @Suppress("DEPRECATION")
                runCatching { wifiManager.isWifiEnabled = !wifiOn() }
                refreshSoon(TOGGLE_REFRESH_DELAY_MS)
            }
        }
        wifiTile.setOnLongClickListener { open(Intent(Settings.ACTION_WIFI_SETTINGS)); true }

        cellularTile.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                open(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            } else {
                open(Intent(Settings.ACTION_DATA_ROAMING_SETTINGS))
            }
        }

        bluetoothTile.setOnClickListener { toggleBluetooth() }
        bluetoothTile.setOnLongClickListener { open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); true }

        hotspotTile.setOnClickListener {
            open(
                Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
                Intent(Settings.ACTION_WIRELESS_SETTINGS)
            )
        }

        vpnTile.setOnClickListener { open(Intent(Settings.ACTION_VPN_SETTINGS)) }
    }

    private fun airplaneOn() = Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0

    private fun wifiOn() = runCatching { wifiManager.isWifiEnabled }.getOrDefault(false)

    private fun mobileDataOn(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val telephony = context.getSystemService(TelephonyManager::class.java)
            val enabled = runCatching { telephony?.isDataEnabled }.getOrNull()
            if (enabled != null) return enabled
        }
        return Settings.Global.getInt(resolver, "mobile_data", 0) == 1
    }

    private val bluetoothAdapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @SuppressLint("MissingPermission")
    private fun bluetoothOn() = runCatching { bluetoothAdapter?.isEnabled == true }.getOrDefault(false)

    /** Switches Bluetooth directly up to Android 11; Android 12+ doesn't allow that without a runtime permission, so it opens the settings. */
    @SuppressLint("MissingPermission")
    private fun toggleBluetooth() {
        val adapter = bluetoothAdapter
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && adapter != null) {
            @Suppress("DEPRECATION")
            runCatching { if (adapter.isEnabled) adapter.disable() else adapter.enable() }
            refreshSoon(TOGGLE_REFRESH_DELAY_MS)
        } else {
            open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    @Suppress("DEPRECATION")
    private fun vpnOn() = runCatching {
        connectivity.allNetworks.any {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }.getOrDefault(false)

    // -------------------------------------------------------------------------------------------
    // Media (needs the notification-listener permission for title / artwork; the buttons work without it)
    // -------------------------------------------------------------------------------------------

    private fun bindMedia() {
        mediaArtwork.clipToOutline = true
        mediaTile.setOnClickListener {
            if (!ControlCenterNotificationListenerService.isEnabled(context)) {
                open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                return@setOnClickListener
            }
            val session = controller?.sessionActivity ?: return@setOnClickListener
            close()
            runCatching { session.send() }
        }
        mediaPrevious.setOnClickListener { mediaAction(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        mediaPlay.setOnClickListener { mediaAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        mediaNext.setOnClickListener { mediaAction(KeyEvent.KEYCODE_MEDIA_NEXT) }
        airPlayButton.setOnClickListener { open(Intent(Settings.ACTION_CAST_SETTINGS)) }
    }

    private fun pickMediaController() {
        controller?.unregisterCallback(controllerCallback)
        val sessions = runCatching { sessionManager.getActiveSessions(listenerComponent) }.getOrNull().orEmpty()
        controller = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
        controller?.registerCallback(controllerCallback, handler)
        updateMedia()
    }

    private fun updateMedia() {
        val metadata = controller?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val artwork = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

        mediaTitle.text = title ?: context.getString(R.string.cc_not_playing)
        mediaArtist.text = when {
            title != null -> artist.orEmpty()
            !ControlCenterNotificationListenerService.isEnabled(context) -> context.getString(R.string.cc_media_allow)
            else -> ""
        }
        mediaArtwork.setImageBitmap(artwork)
        val playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        mediaPlay.setImageResource(if (playing) R.drawable.ic_cc_pause else R.drawable.ic_cc_play)
    }

    private fun mediaAction(keyCode: Int) {
        val current = controller
        if (current != null) {
            val controls = current.transportControls
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                    if (current.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
                KeyEvent.KEYCODE_MEDIA_NEXT -> controls.skipToNext()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> controls.skipToPrevious()
            }
        } else {
            // No permission / no session known: a media key still reaches the last-used player.
            val now = SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        }
        handler.postDelayed({ pickMediaController() }, MEDIA_REFRESH_DELAY_MS)
    }

    // -------------------------------------------------------------------------------------------
    // Camera / Silent / Focus / Flashlight / Rotation / Mirroring / Battery
    // -------------------------------------------------------------------------------------------

    private fun bindControls() {
        cameraTile.setOnClickListener { open(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) }

        silentTile.setOnClickListener {
            if (!notifications.isNotificationPolicyAccessGranted) {
                open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                return@setOnClickListener
            }
            val target = if (silentOn()) AudioManager.RINGER_MODE_NORMAL else AudioManager.RINGER_MODE_VIBRATE
            runCatching { audio.ringerMode = target }
            refreshStates()
        }

        focusTile.setOnClickListener {
            if (!notifications.isNotificationPolicyAccessGranted) {
                open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                return@setOnClickListener
            }
            val filter = if (focusOn()) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY
            runCatching { notifications.setInterruptionFilter(filter) }
            refreshSoon()
        }

        flashlightTile.setOnClickListener { toggleTorch() }

        rotationTile.setOnClickListener {
            if (!Settings.System.canWrite(context)) {
                requestWriteSettings()
                return@setOnClickListener
            }
            runCatching {
                Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, if (rotationLocked()) 1 else 0)
            }
            refreshStates()
        }

        themeTile.setOnClickListener { open(Intent(context, ThemePacksActivity::class.java)) }
        mirroringTile.setOnClickListener { open(Intent(Settings.ACTION_CAST_SETTINGS)) }
        batteryTile.setOnClickListener { open(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }
    }

    private fun silentOn() = audio.ringerMode != AudioManager.RINGER_MODE_NORMAL

    private fun focusOn(): Boolean {
        val filter = notifications.currentInterruptionFilter
        return filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    private fun renderFocus() {
        val on = focusOn()
        if (on) focusBadge.background = circle(FOCUS_ON_COLOR) else focusBadge.setBackgroundResource(R.drawable.bg_cc_circle_off)
        focusDetail.setText(if (on) R.string.cc_on else R.string.cc_off)
    }

    private fun rotationLocked() = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 0

    private fun toggleTorch() {
        val id = torchId ?: return
        val target = !torchOn
        if (runCatching { cameraManager.setTorchMode(id, target) }.isSuccess) {
            torchOn = target
            renderFlashlight()
        }
    }

    private fun renderFlashlight() {
        if (torchOn) flashlightBadge.background = circle(TORCH_ON_COLOR) else flashlightBadge.setBackgroundResource(R.drawable.bg_cc_circle_off)
        flashlightIcon.imageTintList = ColorStateList.valueOf(if (torchOn) TORCH_ON_ICON else Color.WHITE)
        flashlightDetail.setText(if (torchOn) R.string.cc_on else R.string.cc_off)
    }

    // -------------------------------------------------------------------------------------------
    // Sliders
    // -------------------------------------------------------------------------------------------

    private fun setUpSliders() {
        brightnessSlider.icon = ContextCompat.getDrawable(context, R.drawable.ic_cc_brightness)
        brightnessSlider.iconColor = BRIGHTNESS_ICON
        brightnessSlider.listener = object : VerticalSliderView.Listener {
            override fun onStartTracking(): Boolean {
                if (Settings.System.canWrite(context)) return true
                requestWriteSettings() // "Modify system settings", like the reference app
                return false
            }

            override fun onValueChanged(value: Float) = setBrightness(value)
        }

        volumeSlider.icon = ContextCompat.getDrawable(context, R.drawable.ic_cc_volume)
        volumeSlider.iconColor = VOLUME_ICON
        volumeSlider.listener = object : VerticalSliderView.Listener {
            override fun onValueChanged(value: Float) = setVolume(value)
        }
    }

    /** Slider position from the system brightness (0..255) - squared curve, so the slider feels even. */
    private fun currentBrightness(): Float {
        val raw = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, MAX_BRIGHTNESS / 2)
        return sqrt(raw.coerceIn(0, MAX_BRIGHTNESS) / MAX_BRIGHTNESS.toFloat())
    }

    private fun setBrightness(value: Float) {
        val raw = (value * value * MAX_BRIGHTNESS).roundToInt().coerceIn(1, MAX_BRIGHTNESS)
        runCatching {
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, raw)
        }
    }

    private fun currentVolume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
    }

    /** Steps one unit at a time via [AudioManager.adjustStreamVolume] - unlike jumping straight to
     *  an index with setStreamVolume, this is the same call the hardware volume rocker makes and is
     *  respected much more consistently across OEM skins. */
    private fun setVolume(value: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (value * max).roundToInt().coerceIn(0, max)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val direction = when {
            target > current -> AudioManager.ADJUST_RAISE
            target < current -> AudioManager.ADJUST_LOWER
            else -> return
        }
        repeat(kotlin.math.abs(target - current)) {
            runCatching { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0) }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------

    /** Round tile on/off look: glass + white icon when off, [onBackground] + [onIconColor] when on. */
    private fun renderRound(
        tile: View,
        icon: ImageView,
        on: Boolean,
        onBackground: Int = R.drawable.bg_cc_circle_green,
        onIconColor: Int = Color.WHITE
    ) {
        tile.setBackgroundResource(if (on) onBackground else R.drawable.bg_cc_circle_off)
        icon.imageTintList = ColorStateList.valueOf(if (on) onIconColor else Color.WHITE)
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun requestWriteSettings() {
        open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
    }

    /** Hides the panel, then opens the first of [intents] that works. */
    private fun open(vararg intents: Intent) {
        close()
        for (intent in intents) {
            val started = runCatching {
                context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (started) return
        }
    }

    companion object {
        /** Kept across panel openings, so the service can switch the torch off when it stops. */
        @Volatile
        private var torchOn = false

        private const val MAX_BRIGHTNESS = 255
        private const val REFRESH_DELAY_MS = 400L
        private const val TOGGLE_REFRESH_DELAY_MS = 900L
        private const val MEDIA_REFRESH_DELAY_MS = 350L
        private const val CLOCK_UPDATE_MS = 60_000L
        private const val STATUS_WIFI_OFF_ALPHA = 0.4f
        private const val STATUS_BATTERY_CHARGING_COLOR = 0xFF34C759.toInt()

        private const val FOCUS_ON_COLOR = 0xFF5E5CE6.toInt()
        private const val TORCH_ON_COLOR = 0xFFFFD60A.toInt()
        private const val TORCH_ON_ICON = 0xFF1C1C1E.toInt()
        private const val ROTATION_ON_ICON = 0xFF18A107.toInt()
        private const val SILENT_ON_ICON = 0xFF34C759.toInt()
        private const val BATTERY_SAVER_ICON = 0xFFFFCC00.toInt()
        private const val BRIGHTNESS_ICON = 0xFFFFB800.toInt()
        private const val VOLUME_ICON = 0xFF4DA3FF.toInt()

        private fun torchCameraId(manager: CameraManager): String? = runCatching {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()

        /** Called when the service stops, so the flashlight never stays on by accident. */
        fun turnOffTorchIfOn(context: Context) {
            if (!torchOn) return
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            torchCameraId(manager)?.let { id -> runCatching { manager.setTorchMode(id, false) } }
            torchOn = false
        }
    }
}
