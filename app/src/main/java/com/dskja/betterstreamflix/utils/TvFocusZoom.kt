package com.dskja.betterstreamflix.utils

import android.view.View

/** Shared TV focus zoom — skipped on Fire Stick / reduce-motion / low-RAM. */
object TvFocusZoom {
    fun apply(view: View, hasFocus: Boolean) {
        view.clearAnimation()
        view.animate().cancel()
        val reduce = DeviceCapabilities.shouldReduceHomeEffects(view.context)
        val density = view.resources.displayMetrics.density
        view.elevation = if (hasFocus) (if (reduce) 12f else 10f) * density else 0f
        view.isSelected = hasFocus
        if (reduce) {
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        // Scale from the current size. The old zoom_out clip always started at
        // 108%, so a recycled row popped larger than its neighbors.
        view.animate()
            .scaleX(if (hasFocus) 1.08f else 1f)
            .scaleY(if (hasFocus) 1.08f else 1f)
            .setDuration(if (hasFocus) 180L else 120L)
            .start()
    }
}
