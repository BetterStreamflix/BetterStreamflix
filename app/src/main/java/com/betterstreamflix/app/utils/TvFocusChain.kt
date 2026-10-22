package com.betterstreamflix.app.utils

import android.view.View

/** DPAD focus among currently visible, enabled controls. */
object TvFocusChain {
    fun linkHorizontal(vararg views: View) {
        link(views, horizontal = true)
    }

    fun linkVertical(vararg views: View) {
        link(views, horizontal = false)
    }

    /**
     * Pure axis-link plan for JVM tests (FA-035). [visibleIds] are already filtered
     * to focusable/visible nodes; returns prev/next per id. Orthogonal axis is never
     * represented here — callers must not clear it.
     */
    fun planAxisLinks(visibleIds: List<Int>): List<AxisLink> {
        if (visibleIds.isEmpty()) return emptyList()
        return visibleIds.mapIndexed { index, id ->
            AxisLink(
                id = id,
                previousId = visibleIds.getOrNull(index - 1) ?: View.NO_ID,
                nextId = visibleIds.getOrNull(index + 1) ?: View.NO_ID,
            )
        }
    }

    data class AxisLink(
        val id: Int,
        val previousId: Int,
        val nextId: Int,
    )

    private fun link(views: Array<out View>, horizontal: Boolean) {
        val visible = views.filter { it.visibility == View.VISIBLE && it.isFocusable && it.isEnabled }
        views.filter { it !in visible }.forEach { view ->
            // Clear only the axis we are linking — preserve the orthogonal axis
            // so XML / prior vertical (or horizontal) links survive a rewire (FA-002).
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
        val links = planAxisLinks(visible.map { it.id })
        links.forEach { link ->
            val view = visible.first { it.id == link.id }
            if (horizontal) {
                view.nextFocusLeftId = link.previousId
                view.nextFocusRightId = link.nextId
                // Preserve nextFocusUp/Down — transport↔progress and detail CTAs rely on them.
            } else {
                view.nextFocusUpId = link.previousId
                view.nextFocusDownId = link.nextId
                // Preserve nextFocusLeft/Right.
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
