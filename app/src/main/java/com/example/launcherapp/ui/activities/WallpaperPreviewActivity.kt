package com.example.launcherapp.ui.activities

import android.app.Activity
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.example.launcherapp.R
import com.example.launcherapp.databinding.ActivityWallpaperPreviewBinding
import com.example.launcherapp.databinding.DialogSetWallpaperBinding
import com.example.launcherapp.utils.HomeWallpaperStore
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.concurrent.thread

class WallpaperPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWallpaperPreviewBinding
    private var resId: Int = -1
    private var sourceUri: Uri? = null

    private enum class Target { LOCK_SCREEN, HOME_SCREEN, BOTH }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityWallpaperPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        resId = intent.getIntExtra(EXTRA_RES_ID, -1)
        sourceUri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        if (resId != -1) {
            binding.previewImage.setImageResource(resId)
        } else {
            binding.previewImage.setImageURI(sourceUri)
        }

        binding.previewBack.setOnClickListener { finish() }
        binding.previewSetWallpaper.setOnClickListener { showSetWallpaperSheet() }
    }

    /** Pushes the back arrow / button off the status and nav bars - enableEdgeToEdge() draws under both by design, so something has to consume the insets; the preview image itself stays full-bleed. */
    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.previewContent) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
    }

    private fun showSetWallpaperSheet() {
        val sheetBinding = DialogSetWallpaperBinding.inflate(LayoutInflater.from(this))
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheetBinding.root)

        var selected = Target.LOCK_SCREEN
        val options = mapOf(
            Target.LOCK_SCREEN to sheetBinding.optionLockScreen,
            Target.HOME_SCREEN to sheetBinding.optionHomeScreen,
            Target.BOTH to sheetBinding.optionBoth
        )
        fun refreshSelection() {
            options.forEach { (target, view) ->
                view.setBackgroundResource(
                    if (target == selected) R.drawable.bg_wallpaper_option_selected else R.drawable.settings_card_background
                )
            }
        }
        options.forEach { (target, view) ->
            view.setOnClickListener {
                selected = target
                refreshSelection()
            }
        }

        sheetBinding.sheetApply.setOnClickListener {
            dialog.setCancelable(false)
            sheetBinding.sheetContent.isVisible = false
            sheetBinding.applyingOverlay.isVisible = true
            applyWallpaper(selected) { success ->
                if (!success) {
                    Toast.makeText(this, R.string.wallpaper_apply_failed, Toast.LENGTH_SHORT).show()
                    dialog.setCancelable(true)
                    sheetBinding.applyingOverlay.isVisible = false
                    sheetBinding.sheetContent.isVisible = true
                    return@applyWallpaper
                }
                dialog.dismiss()
                setResult(Activity.RESULT_OK)
                finish()
            }
        }

        dialog.show()
    }

    private fun applyWallpaper(target: Target, onDone: (Boolean) -> Unit) {
        thread(name = "apply-wallpaper") {
            val success = runCatching {
                val bitmap = decodeBitmap() ?: error("no source bitmap")
                val manager = WallpaperManager.getInstance(this)
                val flags = when (target) {
                    Target.LOCK_SCREEN -> WallpaperManager.FLAG_LOCK
                    Target.HOME_SCREEN -> WallpaperManager.FLAG_SYSTEM
                    Target.BOTH -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                }
                manager.setBitmap(bitmap, null, true, flags)
                // The launcher's own copy for its home screen (it can't reliably read the phone's
                // wallpaper back, and theme packs need to know a wallpaper was picked).
                if (target != Target.LOCK_SCREEN) saveHomeCopy(bitmap)
            }.isSuccess
            runOnUiThread {
                if (!isFinishing && !isDestroyed) onDone(success)
            }
        }
    }

    /** Bundled wallpaper: saved by name. Uploaded photo: a screen-sized copy in the app's storage. */
    private fun saveHomeCopy(bitmap: Bitmap) {
        if (resId != -1) {
            HomeWallpaperStore.saveResource(this, resId)
        } else {
            HomeWallpaperStore.saveBitmap(this, bitmap)
        }
    }

    private fun decodeBitmap(): Bitmap? = runCatching {
        if (resId != -1) {
            BitmapFactory.decodeResource(resources, resId)
        } else {
            contentResolver.openInputStream(sourceUri!!)?.use(BitmapFactory::decodeStream)
        }
    }.getOrNull()

    companion object {
        const val EXTRA_RES_ID = "extra_res_id"
        const val EXTRA_URI = "extra_uri"
    }
}