package com.dskja.betterstreamflix.logo

import android.view.ViewGroup
import android.widget.ImageView
import com.dskja.betterstreamflix.R

/**
 * Presentation rules for TMDb title logos across Featured, Detail, Player.
 *
 * Hard contrast backgrounds on the ImageView itself crush transparent wordmarks.
 * Soft plates belong on the parent slot (XML) or on the art vignette — never on the logo pixels.
 */
object TitleLogoPresentation {

    fun clearImagePlate(imageView: ImageView) {
        imageView.background = null
    }

    /**
     * Optional soft plate on the logo slot parent when showing fallback title text
     * on surfaces that do not already ship a vignette (rare). Featured/Detail rely on art scrims.
     */
    fun applySlotPlate(imageView: ImageView, showPlate: Boolean) {
        if (!showPlate) return
        val parent = imageView.parent as? ViewGroup ?: return
        if (parent.background == null) {
            parent.setBackgroundResource(R.drawable.bg_title_logo_contrast)
        }
    }
}
