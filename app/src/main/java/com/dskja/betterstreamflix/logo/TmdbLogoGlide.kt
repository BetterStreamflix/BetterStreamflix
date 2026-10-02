package com.dskja.betterstreamflix.logo

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.View
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.ArtworkUrls

/**
 * Shared Glide load for TMDb title logos:
 * original → w1280 → w500 → w300, with identity tags, view-size override, and optional flash-hide.
 */
object TmdbLogoGlide {

    private val LOGO_URL_TAG = R.id.tmdb_logo_expected_url_tag

    fun clear(imageView: ImageView) {
        Glide.with(imageView).clear(imageView)
        imageView.setImageDrawable(null)
        imageView.setTag(LOGO_URL_TAG, null)
    }

    fun prefetch(context: Context, logoUrl: String?, wifiOnly: Boolean = true) {
        if (wifiOnly && isMetered(context)) return
        val url = ArtworkUrls.preferOriginal(logoUrl)
            ?: ArtworkUrls.preferHero(logoUrl)
            ?: return
        Glide.with(context.applicationContext)
            .load(url)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .preload()
    }

    fun load(
        imageView: ImageView,
        logoUrl: String?,
        stillCurrent: () -> Boolean = { true },
        hideUntilReady: Boolean = false,
        contentDescription: String? = null,
        onFailed: () -> Unit,
        onReady: (readyUrl: String) -> Unit,
    ) {
        if (!contentDescription.isNullOrBlank()) {
            imageView.contentDescription = contentDescription
            imageView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val sizes = ArtworkUrls.logoSizeLadder(logoUrl)
        if (sizes.isEmpty()) {
            if (stillCurrent()) onFailed()
            return
        }
        if (hideUntilReady) {
            imageView.visibility = View.INVISIBLE
        }
        // Prefer height-based decode so wide match_parent slots do not squash wordmarks.
        val overrideH = imageView.height.takeIf { it > 0 }
            ?: imageView.layoutParams?.height?.takeIf { it > 0 }
        val overrideW = overrideH?.let { h ->
            val laidOutW = imageView.width.takeIf { it > 0 }
                ?: imageView.layoutParams?.width?.takeIf { it > 0 }
            // Cap at ~3× height for typical TMDb logo aspect; never exceed laid-out width.
            val budget = (h * 3).coerceAtLeast(h)
            laidOutW?.coerceAtMost(budget) ?: budget
        }

        fun attempt(index: Int) {
            val url = sizes.getOrNull(index)
            if (url.isNullOrBlank()) {
                if (stillCurrent()) {
                    TmdbLogoTelemetry.recordGlideFail()
                    if (TmdbLogoPicker.isTrustedTmdbLogo(logoUrl)) {
                        TmdbLogoCache.markDecodeFailed(logoUrl)
                    }
                    onFailed()
                }
                return
            }
            if (TmdbLogoCache.isBlacklisted(url)) {
                attempt(index + 1)
                return
            }
            imageView.setTag(LOGO_URL_TAG, url)
            // Let ImageView scaleType (fitStart / fitCenter) own alignment — no Glide transform.
            var request = Glide.with(imageView)
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .dontTransform()
                .transition(
                    com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(180),
                )
            if (overrideW != null && overrideH != null && overrideW > 0 && overrideH > 0) {
                request = request.apply(RequestOptions().override(overrideW, overrideH))
            }
            request
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean,
                    ): Boolean {
                        if (!stillCurrent()) return false
                        if (imageView.getTag(LOGO_URL_TAG) != url) return false
                        attempt(index + 1)
                        return true
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean,
                    ): Boolean {
                        if (!stillCurrent()) return false
                        if (imageView.getTag(LOGO_URL_TAG) != url) return false
                        // Reject degenerate 1×1 / empty draws.
                        if (resource.intrinsicWidth <= 1 || resource.intrinsicHeight <= 1) {
                            attempt(index + 1)
                            return true
                        }
                        if (hideUntilReady) {
                            imageView.visibility = View.VISIBLE
                        }
                        onReady(url)
                        return false
                    }
                })
                .into(imageView)
        }

        attempt(0)
    }

    private fun isMetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
