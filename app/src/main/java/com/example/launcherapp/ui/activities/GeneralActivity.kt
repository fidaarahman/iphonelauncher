package com.example.launcherapp.ui.activities

import android.content.Intent
import android.os.Bundle
import android.widget.SeekBar
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.example.launcherapp.R
import com.example.launcherapp.databinding.ActivityGeneralBinding
import com.example.launcherapp.ui.model.TextSizeSettings

/** Blur Effect isn't wired to anything yet - Flexible Text Size is (see [TextSizeSettings]). */
class GeneralActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGeneralBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityGeneralBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        binding.generalBack.setOnClickListener { finish() }
        binding.rowPageTransition.setOnClickListener {
            startActivity(Intent(this, PageTransitionActivity::class.java))
        }

        setUpFlexibleTextSize()
    }

    private fun setUpFlexibleTextSize() {
        binding.switchFlexibleTextSize.isChecked = TextSizeSettings.enabled
        binding.textSizeSeekBar.progress = TextSizeSettings.progress
        refreshTextSizePreview()

        binding.switchFlexibleTextSize.setOnCheckedChangeListener { _, checked ->
            TextSizeSettings.enabled = checked
            refreshTextSizePreview()
        }
        binding.textSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                TextSizeSettings.progress = progress
                refreshTextSizePreview()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    /** Live "108%" label + dims the preview/slider while the feature is off - the saved value stays visible either way. */
    private fun refreshTextSizePreview() {
        val enabled = binding.switchFlexibleTextSize.isChecked
        val percent = TextSizeSettings.percentFor(binding.textSizeSeekBar.progress)
        binding.textSizePercentLabel.text = getString(R.string.general_text_size_percent, percent)

        // "Default" only means something at the slider's 100% point - anywhere else the size is custom.
        val isDefaultScale = percent == 100
        binding.textSizeDot.isVisible = isDefaultScale
        binding.textSizeDefaultLabel.isVisible = isDefaultScale

        binding.textSizeSeekBar.isEnabled = enabled
        binding.textSizePreview.alpha = if (enabled) 1f else 0.4f
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.generalRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
    }
}
