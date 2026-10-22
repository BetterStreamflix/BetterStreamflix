package com.betterstreamflix.app.logo

import android.content.Context
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
import com.betterstreamflix.app.ui.LettermarkDrawable
import com.betterstreamflix.app.utils.DeviceCapabilities
import com.betterstreamflix.app.utils.UserPreferences

/**
 * Unified channel-logo binder: normalized URL → Glide (SVG-aware) →
 * [LettermarkDrawable] fallback, with persisted last-good/blacklist state via
 * [ChannelLogoStore] and metered-aware prefetch.
 */
object ChannelLogoLoader {

    fun bind(imageView: ImageView, channelId: String, name: String, url: String?) {
        imageView.contentDescription = name
        imageView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        val effective = ChannelLogoStore.effectiveUrl(channelId, url)
        if (effective == null) {
            Glide.with(imageView).clear(imageView)
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
                .listener(SvgListener(imageView, channelId, name, effective))
                .into(imageView)
        } else {
            imageView.setLayerType(View.LAYER_TYPE_NONE, null)
            Glide.with(imageView)
                .load(effective)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .error(LettermarkDrawable(name))
                .listener(BitmapListener(imageView, channelId, name, effective))
                .into(imageView)
        }
    }

    /** Idle warm-up for guide windows — unmetered only, honors data saver. */
    fun prefetch(context: Context, channelId: String, url: String?) {
        if (UserPreferences.dataSaverMode) return
        val effective = ChannelLogoStore.effectiveUrl(channelId, url) ?: return
        if (!FeaturedLogoEnrich.isDefinitelyUnmetered(context)) return
        val app = context.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { Glide.with(app).load(effective).preload() }
        }
    }

    private class BitmapListener(
        private val imageView: ImageView,
        private val channelId: String,
        private val name: String,
        private val url: String,
    ) : RequestListener<Drawable> {
        override fun onLoadFailed(
            e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean,
        ): Boolean {
            ChannelLogoStore.recordFailure(url)
            imageView.setImageDrawable(LettermarkDrawable(name))
            return true
        }

        override fun onResourceReady(
            resource: Drawable, model: Any, target: Target<Drawable>?,
            dataSource: DataSource, isFirstResource: Boolean,
        ): Boolean {
            ChannelLogoStore.recordSuccess(channelId, url)
            return false
        }
    }

    private class SvgListener(
        private val imageView: ImageView,
        private val channelId: String,
        private val name: String,
        private val url: String,
    ) : RequestListener<PictureDrawable> {
        override fun onLoadFailed(
            e: GlideException?, model: Any?, target: Target<PictureDrawable>, isFirstResource: Boolean,
        ): Boolean {
            ChannelLogoStore.recordFailure(url)
            imageView.setImageDrawable(LettermarkDrawable(name))
            return true
        }

        override fun onResourceReady(
            resource: PictureDrawable, model: Any, target: Target<PictureDrawable>?,
            dataSource: DataSource, isFirstResource: Boolean,
        ): Boolean {
            ChannelLogoStore.recordSuccess(channelId, url)
            return false
        }
    }
}
