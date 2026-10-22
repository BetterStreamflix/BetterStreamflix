package com.betterstreamflix.app.utils

import android.view.View
import android.view.ViewGroup

/**
 * Fire OS Leanback treats a focusable full-screen loading overlay as a DPAD trap
 * (process-kill class). Keep the overlay non-focusable by default and re-disarm
 * on every Loading / Success / Failed transition.
 */
object TvLoadingOverlay {
    fun disarm(root: View?) {
        root ?: return
        if (root.hasFocus() || root.findFocus() != null) {
            root.findFocus()?.clearFocus()
            root.clearFocus()
        }
        root.isFocusable = false
        root.isFocusableInTouchMode = false
        root.isClickable = false
        if (root is ViewGroup) {
            root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
    }

    /** Show loader without ever owning focus. Retry CTAs remain focusable children. */
    fun show(root: View?) {
        disarm(root)
        root?.visibility = View.VISIBLE
        ExpPressEffects.showLoadingSkeleton(root, true)
    }

    fun hide(root: View?) {
        disarm(root)
        root?.visibility = View.GONE
        ExpPressEffects.showLoadingSkeleton(root, false)
    }
}
