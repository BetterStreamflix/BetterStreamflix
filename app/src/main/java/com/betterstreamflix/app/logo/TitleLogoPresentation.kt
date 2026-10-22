package com.betterstreamflix.app.logo

import android.view.ViewGroup
import android.widget.ImageView
import com.betterstreamflix.app.R

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
     * Wordmark-friendly ImageView defaults. Preserves XML fitStart (Detail) / fitCenter
     * (Featured) so header/hero/Featured alignment stay intentional.
     */
    fun polishLogoImage(imageView: ImageView) {
        clearImagePlate(imageView)
        clearSlotPlate(imageView)
        when (imageView.scaleType) {
            ImageView.ScaleType.FIT_CENTER,
            ImageView.ScaleType.FIT_START,
            ImageView.ScaleType.CENTER_INSIDE,
            -> Unit
            else -> imageView.scaleType = ImageView.ScaleType.FIT_START
        }
        imageView.adjustViewBounds = true
        imageView.cropToPadding = false
        // Max-width clamp: wordmarks wider than the slot must shrink, not clip.
        val parentWidth = (imageView.parent as? ViewGroup)?.width
        if (parentWidth != null && parentWidth > 0) {
            imageView.maxWidth = (parentWidth * 0.85f).toInt()
        }
        // Soft horizontal inset so wide logos breathe without a hard plate.
        val density = imageView.resources.displayMetrics.density
        val padH = (2 * density).toInt()
        val padV = (4 * density).toInt()
        if (imageView.paddingLeft < padH || imageView.paddingTop < padV) {
            imageView.setPadding(padH, padV, padH, padV)
        }
    }

    /**
     * Adaptive slot height: grow/shrink the ImageView height within [min,max] so
     * very wide wordmarks (aspect > ~3) get more room instead of shrinking to a
     * sliver. Opt-in per surface — wrapped in layoutParams.height units px.
     */
    fun adaptSlotForAspect(imageView: ImageView, aspect: Float, minDp: Float = 40f, maxDp: Float = 88f) {
        if (!aspect.isFinite() || aspect <= 0f) return
        val density = imageView.resources.displayMetrics.density
        val width = imageView.width.takeIf { it > 0 }
            ?: (imageView.parent as? ViewGroup)?.width ?: return
        val targetH = (width / aspect).coerceIn(minDp * density, maxDp * density).toInt()
        val lp = imageView.layoutParams ?: return
        if (lp.height != targetH) {
            lp.height = targetH
            imageView.layoutParams = lp
        }
    }

    /**
     * Soft drop-shadow: a dark-tinted copy offset 1px under the logo. Transparent
     * wordmarks stay legible on bright/loaded backdrops without a hard plate.
     * Idempotent — re-wrapping a LayerDrawable returns it unchanged.
     */
    fun withSoftShadow(drawable: android.graphics.drawable.Drawable): android.graphics.drawable.Drawable {
        if (drawable is android.graphics.drawable.LayerDrawable) return drawable
        val shadow = drawable.constantState?.newDrawable()?.mutate()
            ?: return drawable
        shadow.colorFilter = android.graphics.PorterDuffColorFilter(
            android.graphics.Color.argb(110, 0, 0, 0),
            android.graphics.PorterDuff.Mode.SRC_IN,
        )
        return android.graphics.drawable.LayerDrawable(arrayOf(shadow, drawable)).apply {
            setLayerInset(0, 0, 2, 0, 0)
        }
    }

    /** Vibrant accent color from a decoded logo bitmap for hero/chrome accents. */
    fun extractAccentColor(drawable: android.graphics.drawable.Drawable?): Int? {
        val bitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            ?: return null
        return androidx.palette.graphics.Palette.from(bitmap).generate().getVibrantColor(0)
            .takeIf { it != 0 }
    }

    /**
     * Optional soft plate on the logo slot parent when showing fallback title text
     * on surfaces that do not already ship a vignette (rare). Featured/Detail rely on art scrims.
     */
    fun applySlotPlate(imageView: ImageView, showPlate: Boolean) {
        if (!showPlate) {
            clearSlotPlate(imageView)
            return
        }
        val parent = imageView.parent as? ViewGroup ?: return
        if (parent.background == null) {
            parent.setBackgroundResource(R.drawable.bg_title_logo_contrast)
        }
    }

    /** Drop any soft slot plate so logo pixels never sit on an empty dark card. */
    fun clearSlotPlate(imageView: ImageView) {
        val parent = imageView.parent as? ViewGroup ?: return
        parent.background = null
    }
}
