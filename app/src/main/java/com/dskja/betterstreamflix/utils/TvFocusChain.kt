package com.dskja.betterstreamflix.utils

import android.view.View

/** DPAD left/right focus among currently visible action buttons. */
object TvFocusChain {
    fun linkHorizontal(vararg views: View) {
        val visible = views.filter { it.visibility == View.VISIBLE && it.isFocusable }
        visible.forEachIndexed { index, view ->
            view.nextFocusLeftId = visible.getOrNull(index - 1)?.id ?: View.NO_ID
            view.nextFocusRightId = visible.getOrNull(index + 1)?.id ?: View.NO_ID
        }
    }

    /** DPAD down from the primary CTA row onto the first visible secondary action. */
    fun linkDown(from: List<View>, target: View?) {
        val id = target
            ?.takeIf { it.visibility == View.VISIBLE && it.isFocusable }
            ?.id
            ?: View.NO_ID
        from.filter { it.visibility == View.VISIBLE }.forEach { view ->
            view.nextFocusDownId = id
        }
    }
}
