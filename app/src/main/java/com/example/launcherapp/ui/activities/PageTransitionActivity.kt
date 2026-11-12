package com.example.launcherapp.ui.activities

import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.example.launcherapp.databinding.ActivityPageTransitionBinding
import com.example.launcherapp.ui.model.PageTransition
import com.example.launcherapp.utils.SessionManager

/** Picking a row here is applied immediately - LauncherHomeActivity re-reads it on its next onResume(). */
class PageTransitionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPageTransitionBinding
    private lateinit var checks: List<ImageView>
    private val transitions = listOf(PageTransition.DEFAULT, PageTransition.ZOOM_IN, PageTransition.ALPHA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityPageTransitionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        checks = listOf(binding.optionDefaultCheck, binding.optionZoomInCheck, binding.optionAlphaCheck)
        val rows: List<LinearLayout> = listOf(binding.optionDefault, binding.optionZoomIn, binding.optionAlpha)
        rows.forEachIndexed { index, row ->
            row.setOnClickListener { select(index) }
        }

        val current = PageTransition.find(SessionManager.getString(PageTransition.PREF_KEY, ""))
        select(transitions.indexOf(current))

        binding.pageTransitionBack.setOnClickListener { finish() }
    }

    private fun select(selectedIndex: Int) {
        checks.forEachIndexed { index, check -> check.isVisible = index == selectedIndex }
        SessionManager.setString(PageTransition.PREF_KEY, transitions[selectedIndex].id)
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.pageTransitionRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
    }
}
