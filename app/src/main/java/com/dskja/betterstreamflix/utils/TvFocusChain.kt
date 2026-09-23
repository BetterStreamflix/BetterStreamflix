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
}
