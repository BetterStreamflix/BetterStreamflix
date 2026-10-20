package com.dskja.betterstreamflix.ui

import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.dskja.betterstreamflix.models.TrailerCatalog

/**
 * Loads YouTube trailer thumbnails with a graceful quality fallback:
 * the preferred rung is tried first, missing variants (maxres/sd 404s)
 * step down the chain instead of leaving an empty card.
 */
object TrailerThumbLoader {

    fun into(
        target: ImageView,
        videoId: String,
        quality: String? = TrailerCatalog.THUMB_QUALITY_DEFAULT,
        crossfadeMs: Int = 160,
        listener: RequestListener<Drawable>? = null,
    ) {
        val urls = TrailerCatalog.youtubeThumbChain(videoId, quality)
        // Nest error fallbacks from the safest rung outward so each failure
        // delegates to the next URL in the chain.
        var builder: RequestBuilder<Drawable> = Glide.with(target)
            .load(urls.last())
            .centerCrop()
        for (url in urls.dropLast(1).asReversed()) {
            builder = Glide.with(target)
                .load(url)
                .centerCrop()
                .error(builder)
        }
        if (listener != null) builder = builder.addListener(listener)
        builder
            .transition(DrawableTransitionOptions.withCrossFade(crossfadeMs))
            .into(target)
    }
}
