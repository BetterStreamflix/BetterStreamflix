package com.dskja.betterstreamflix.utils

import android.view.View
import android.view.animation.AnimationUtils
import com.dskja.betterstreamflix.R

/** Shared TV focus zoom — skipped on Fire Stick / reduce-motion / low-RAM. */
object TvFocusZoom {
    fun apply(view: View, hasFocus: Boolean) {
        if (DeviceCapabilities.shouldReduceHomeEffects(view.context)) {
            view.clearAnimation()
            // Still lift elevation so focus remains readable without motion.
            view.elevation = if (hasFocus) 12f * view.resources.displayMetrics.density else 0f
            view.isSelected = hasFocus
            return
        }
        view.elevation = if (hasFocus) 10f * view.resources.displayMetrics.density else 0f
        view.isSelected = hasFocus
        val anim = AnimationUtils.loadAnimation(
            view.context,
            if (hasFocus) R.anim.zoom_in else R.anim.zoom_out,
        )
        anim.fillAfter = true
        view.startAnimation(anim)
    }
}
