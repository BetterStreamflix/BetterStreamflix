package com.betterstreamflix.app.logo

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.PictureDrawable
import android.view.View
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.betterstreamflix.app.R
import com.betterstreamflix.app.ui.LettermarkDrawable
import com.betterstreamflix.app.utils.DeviceCapabilities

/**
 * Unified provider-logo binder: normalized URL → Glide (SVG-aware) →
 * lettermark fallback, with last-good/blacklist persistence via
 * [ProviderLogoStore]. Bright/white raster logos get a gentle dim in the dark
 * Cinema-Ink theme so they don't glare on provider tiles.
 */
object ProviderLogoLoader {

    /** Above this average luminance a raster logo gets toned down for dark UI. */
    private const val BRIGHT_LOGO_LUMINANCE = 0.80
    private const val DIM_COLOR = 0xFFDDDDDD.toInt()

    fun bind(imageView: ImageView, providerKey: String, name: String, url: String?) {
        imageView.contentDescription = name
        val effective = ProviderLogoStore.effectiveUrl(providerKey, url)
        if (effective == null) {
            Glide.with(imageView).clear(imageView)
            imageView.clearColorFilter()
            imageView.setLayerType(View.LAYER_TYPE_NONE, null)
            imageView.setImageDrawable(LettermarkDrawable(name))
            return
        }
        val isSvg = effective.substringBefore('?').endsWith(".svg", ignoreCase = true)
        if (isSvg) {
            val leanback = DeviceCapabilities.isLeanbackDevice(imageView.context)
            imageView.setLayerType(
                if (leanback) View.LAYER_TYPE_NONE else View.LAYER_TYPE_SOFTWARE,
                null,
            )
            Glide.with(imageView)
                .`as`(PictureDrawable::class.java)
                .load(effective)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .error(R.drawable.ic_provider_default_logo)
                .listener(SvgListener(providerKey, effective))
                .into(imageView)
        } else {
            imageView.setLayerType(View.LAYER_TYPE_NONE, null)
            Glide.with(imageView)
                .load(effective)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .fitCenter()
                .error(R.drawable.ic_provider_default_logo)
                .listener(BitmapListener(imageView, providerKey, effective))
                .into(imageView)
        }
    }

    /** Fallback when no URL ever worked: lettermark > generic icon. */
    fun bindLettermark(imageView: ImageView, name: String) {
        Glide.with(imageView).clear(imageView)
        imageView.setLayerType(View.LAYER_TYPE_NONE, null)
        imageView.setImageDrawable(LettermarkDrawable(name))
        imageView.contentDescription = name
    }

    /** Dim near-white raster logos so they sit better on the dark theme. */
    private fun dimIfBright(imageView: ImageView, drawable: Drawable?) {
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return
        val w = bitmap.width.coerceAtMost(8)
        val h = bitmap.height.coerceAtMost(8)
        if (w <= 0 || h <= 0) return
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        var lum = 0.0
        var alphaTotal = 0
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        for (c in px) {
            val a = Color.alpha(c)
            if (a < 40) continue // transparent — skip
            alphaTotal++
            lum += (0.2126 * Color.red(c) + 0.7152 * Color.green(c) +
                0.0722 * Color.blue(c)) / 255.0
        }
        if (alphaTotal == 0) return
        if (lum / alphaTotal > BRIGHT_LOGO_LUMINANCE) {
            imageView.setColorFilter(DIM_COLOR, android.graphics.PorterDuff.Mode.MULTIPLY)
        } else {
            imageView.clearColorFilter()
        }
    }

    private class BitmapListener(
        private val imageView: ImageView,
        private val providerKey: String,
        private val url: String,
    ) : RequestListener<Drawable> {
        override fun onLoadFailed(
            e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean,
        ): Boolean {
            ProviderLogoStore.recordFailure(url)
            imageView.clearColorFilter()
            return false // let .error() drawable show
        }

        override fun onResourceReady(
            resource: Drawable, model: Any, target: Target<Drawable>?,
            dataSource: DataSource, isFirstResource: Boolean,
        ): Boolean {
            ProviderLogoStore.recordSuccess(providerKey, url)
            dimIfBright(imageView, resource)
            return false
        }
    }

    private class SvgListener(
        private val providerKey: String,
        private val url: String,
    ) : RequestListener<PictureDrawable> {
        override fun onLoadFailed(
            e: GlideException?, model: Any?, target: Target<PictureDrawable>, isFirstResource: Boolean,
        ): Boolean {
            ProviderLogoStore.recordFailure(url)
            return false
        }

        override fun onResourceReady(
            resource: PictureDrawable, model: Any, target: Target<PictureDrawable>?,
            dataSource: DataSource, isFirstResource: Boolean,
        ): Boolean {
            ProviderLogoStore.recordSuccess(providerKey, url)
            return false
        }
    }
}
