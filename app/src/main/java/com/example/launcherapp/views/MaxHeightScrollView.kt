package com.example.launcherapp.views

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/** Plain ScrollView that caps its own height instead of growing to fit all content. */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    var maxHeightPx: Int = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cappedHeightSpec = if (maxHeightPx == Int.MAX_VALUE) {
            heightMeasureSpec
        } else {
            MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, cappedHeightSpec)
    }
}
