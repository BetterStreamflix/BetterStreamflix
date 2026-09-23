package com.dskja.betterstreamflix.utils

import android.view.View
import android.view.animation.AnimationUtils
import com.dskja.betterstreamflix.R

/** Shared TV focus zoom — skipped on Fire Stick / reduce-motion / low-RAM. */
object TvFocusZoom {
    fun apply(view: View, hasFocus: Boolean) {
        if (DeviceCapabilities.shouldReduceHomeEffects(view.context)) {
            view.clearAnimation()
            return
        }
        val anim = AnimationUtils.loadAnimation(
            view.context,
            if (hasFocus) R.anim.zoom_in else R.anim.zoom_out,
        )
        anim.fillAfter = true
        view.startAnimation(anim)
    }
}
