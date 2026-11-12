package com.example.launcherapp.ui.model

import android.view.View
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
import kotlin.math.max

/** The home screen's page-swipe animation, picked on the Page Transition screen. */
enum class PageTransition(val id: String) {
    DEFAULT("default"),
    ZOOM_IN("zoom_in"),
    ALPHA("alpha");

    companion object {
        const val PREF_KEY = "page_transition"

        fun find(id: String?): PageTransition = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

object PageTransitions {

    fun transformerFor(transition: PageTransition): ViewPager2.PageTransformer = when (transition) {
        PageTransition.DEFAULT -> DefaultPageTransformer()
        PageTransition.ZOOM_IN -> ZoomInPageTransformer()
        PageTransition.ALPHA -> AlphaPageTransformer()
    }
}

/**
 * Resets every property the other two transformers touch, back to identity - without this, a page
 * recycled by the RecyclerView ViewPager2 sits on top of could be left scaled/faded/translated from
 * whichever transition was active before (itemAnimator is disabled on this pager, so nothing else
 * would ever clear those). Plain sliding is then just ViewPager2's own normal page layout.
 */
private class DefaultPageTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        page.translationX = 0f
        page.alpha = 1f
        page.scaleX = 1f
        page.scaleY = 1f
    }
}

/** Classic "zoom" transition: the centered page is full-size, neighbours shrink and fade as they slide off. */
private class ZoomInPageTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        page.apply {
            when {
                position < -1f || position > 1f -> alpha = 0f
                else -> {
                    val scale = max(MIN_SCALE, 1 - abs(position))
                    val vertMargin = height * (1 - scale) / 2
                    val horzMargin = width * (1 - scale) / 2
                    translationX = if (position < 0) horzMargin - vertMargin / 2 else -horzMargin + vertMargin / 2
                    scaleX = scale
                    scaleY = scale
                    alpha = MIN_ALPHA + (scale - MIN_SCALE) / (1 - MIN_SCALE) * (1 - MIN_ALPHA)
                }
            }
        }
    }

    private companion object {
        const val MIN_SCALE = 0.85f
        const val MIN_ALPHA = 0.5f
    }
}

/** Pages cross-fade in place instead of sliding - translationX cancels ViewPager2's own slide offset. */
private class AlphaPageTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        page.translationX = -position * page.width
        page.alpha = 1 - abs(position).coerceIn(0f, 1f)
    }
}
