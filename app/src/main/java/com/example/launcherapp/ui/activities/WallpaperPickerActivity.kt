package com.example.launcherapp.ui.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.launcherapp.R
import com.example.launcherapp.databinding.ActivityWallpaperPickerBinding

/**
 * "Select Wallpaper" grid - bundled wallpapers plus an upload tile that opens the system photo
 * picker. Tapping any tile opens [WallpaperPreviewActivity] to actually apply it; this activity
 * just finishes itself once that reports back that a wallpaper was applied, so the user lands
 * back on the home screen.
 */
class WallpaperPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWallpaperPickerBinding

    private val previewLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) finish()
    }
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) openPreview(uri = uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityWallpaperPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        binding.wallpaperPickerBack.setOnClickListener { finish() }
        binding.tileUpload.setOnClickListener {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.tileWallpaper1.setOnClickListener { openPreview(resId = R.drawable.walpaper_1) }
        binding.tileWallpaper2.setOnClickListener { openPreview(resId = R.drawable.walpaper_2) }
        binding.tileWallpaper3.setOnClickListener { openPreview(resId = R.drawable.walpaper_3) }
        binding.tileWallpaper4.setOnClickListener { openPreview(resId = R.drawable.walpaper_4) }
        binding.tileWallpaper5.setOnClickListener { openPreview(resId = R.drawable.walpaper_5) }
    }

    private fun openPreview(resId: Int? = null, uri: Uri? = null) {
        val intent = Intent(this, WallpaperPreviewActivity::class.java)
        if (resId != null) intent.putExtra(WallpaperPreviewActivity.EXTRA_RES_ID, resId)
        if (uri != null) intent.putExtra(WallpaperPreviewActivity.EXTRA_URI, uri.toString())
        previewLauncher.launch(intent)
    }

    /** Pushes the top bar below the status bar - enableEdgeToEdge() draws under it by design, so something has to consume the inset. */
    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.wallpaperPickerRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
    }
}
