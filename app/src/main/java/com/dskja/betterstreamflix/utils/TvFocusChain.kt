package com.dskja.betterstreamflix.utils

import android.view.View

/** DPAD focus among currently visible, enabled controls. */
object TvFocusChain {
    fun linkHorizontal(vararg views: View) {
        link(views, horizontal = true)
    }

    fun linkVertical(vararg views: View) {
        link(views, horizontal = false)
    }

    private fun link(views: Array<out View>, horizontal: Boolean) {
        val visible = views.filter { it.visibility == View.VISIBLE && it.isFocusable && it.isEnabled }
        views.filter { it !in visible }.forEach { view ->
            if (horizontal) {
                view.nextFocusLeftId = View.NO_ID
                view.nextFocusRightId = View.NO_ID
            } else {
                view.nextFocusUpId = View.NO_ID
                view.nextFocusDownId = View.NO_ID
            }
        }
        visible.forEach { view ->
            if (view.id == View.NO_ID) view.id = View.generateViewId()
        }
        visible.forEachIndexed { index, view ->
            val previous = visible.getOrNull(index - 1)?.id ?: View.NO_ID
            val next = visible.getOrNull(index + 1)?.id ?: View.NO_ID
            if (horizontal) {
                view.nextFocusLeftId = previous
                view.nextFocusRightId = next
            } else {
                view.nextFocusUpId = previous
                view.nextFocusDownId = next
            }
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
