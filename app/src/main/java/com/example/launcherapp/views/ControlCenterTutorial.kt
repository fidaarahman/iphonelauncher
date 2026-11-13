package com.example.launcherapp.ui.views

import android.animation.ValueAnimator
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import com.example.launcherapp.R
import com.example.launcherapp.databinding.DialogControlCenterTutorialBinding

object ControlCenterTutorial {

    private val HIGHLIGHT_COLOR = Color.parseColor("#FFD60A")
    private const val POINTER_TOP_DP = 12f
    private const val BOUNCE_MS = 1400L
    private const val BOUNCE_DP = 70f

    fun bind(
        tutorial: DialogControlCenterTutorialBinding,
        onEdgeSettings: () -> Unit,
        onGotIt: () -> Unit
    ): ValueAnimator {
        val context = tutorial.root.context
        val density = context.resources.displayMetrics.density

        // "Hold & drag" in yellow, the rest in white.
        val highlight = context.getString(R.string.cc_tutorial_highlight)
        tutorial.tutorialTitle.text = SpannableStringBuilder(highlight).apply {
            setSpan(ForegroundColorSpan(HIGHLIGHT_COLOR), 0, highlight.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(" ")
            append(context.getString(R.string.cc_tutorial_rest))
        }

        tutorial.tutorialPointer.post {
            val width = tutorial.root.width
            val params = tutorial.tutorialPointer.layoutParams as FrameLayout.LayoutParams
            params.marginStart = (width * 0.75f - tutorial.tutorialPointer.width / 2f).toInt().coerceAtLeast(0)
            params.topMargin = (POINTER_TOP_DP * density).toInt()
            tutorial.tutorialPointer.layoutParams = params
            tutorial.tutorialPointer.visibility = View.VISIBLE
        }

        tutorial.tutorialEdgeSettings.setOnClickListener { onEdgeSettings() }
        tutorial.tutorialGotIt.setOnClickListener { onGotIt() }
        tutorial.root.setOnClickListener { /* swallow taps on the dim background */ }

        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BOUNCE_MS
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                tutorial.tutorialPointer.translationY = t * density * BOUNCE_DP
                tutorial.tutorialPointer.alpha = if (t < 0.8f) 1f else (1f - t) / 0.2f
            }
            start()
        }
    }
}