package com.betterstreamflix.app.logo

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.betterstreamflix.app.R
import com.betterstreamflix.app.utils.ArtworkUrls
import com.betterstreamflix.app.utils.UserPreferences

/**
 * Shared Glide load for TMDb title logos:
 * original → w1280 → w500 → w300, with identity tags, full view-size decode, and optional flash-hide.
 */
object TmdbLogoGlide {

    private val LOGO_URL_TAG = R.id.tmdb_logo_expected_url_tag
    private val mainHandler = Handler(Looper.getMainLooper())

    fun clear(imageView: ImageView) {
        Glide.with(imageView).clear(imageView)
        imageView.setImageDrawable(null)
        imageView.setTag(LOGO_URL_TAG, null)
    }

    fun prefetch(context: Context, logoUrl: String?, wifiOnly: Boolean = true) {
        if (UserPreferences.dataSaverMode) return
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
        onAspect: ((Float) -> Unit)? = null,
        onAccent: ((Int) -> Unit)? = null,
        onFailed: () -> Unit,
        onReady: (readyUrl: String) -> Unit,
    ) {
        if (!contentDescription.isNullOrBlank()) {
            imageView.contentDescription = contentDescription
            imageView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val isSvg = logoUrl?.substringBefore('?')
            ?.endsWith(".svg", ignoreCase = true) == true
        if (isSvg) {
            loadSvg(imageView, logoUrl, stillCurrent, hideUntilReady, onAspect, onFailed, onReady)
            return
        }
        val sizes = ArtworkUrls.logoSizeLadder(logoUrl)
        if (sizes.isEmpty()) {
            if (stillCurrent()) onFailed()
            return
        }
        if (hideUntilReady) {
            imageView.visibility = View.INVISIBLE
        }
        // 1:1 with the laid-out slot. A 3×-height cap upscaled wide wordmarks.
        val overrideW = imageView.width.takeIf { it > 0 }
        val overrideH = imageView.height.takeIf { it > 0 }

        var lastError: com.bumptech.glide.load.engine.GlideException? = null
        fun attempt(index: Int) {
            val url = sizes.getOrNull(index)
            if (url.isNullOrBlank()) {
                if (stillCurrent()) {
                    TmdbLogoTelemetry.recordGlideFail()
                    classifyFailure(lastError)
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
                .format(DecodeFormat.PREFER_ARGB_8888)
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
                        lastError = e
                        // Never start/clear a load synchronously inside a Glide
                        // callback (BETTERSTREAMFLIX-2Q CallbackException).
                        mainHandler.post {
                            if (stillCurrent() && imageView.getTag(LOGO_URL_TAG) == url) {
                                attempt(index + 1)
                            }
                        }
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
                            mainHandler.post {
                                if (stillCurrent() && imageView.getTag(LOGO_URL_TAG) == url) {
                                    attempt(index + 1)
                                }
                            }
                            return true
                        }
                        if (hideUntilReady) {
                            imageView.visibility = View.VISIBLE
                        }
                        onAspect?.invoke(
                            resource.intrinsicWidth.toFloat() / resource.intrinsicHeight,
                        )
                        if (onAccent != null) {
                            TitleLogoPresentation.extractAccentColor(resource)
                                ?.let(onAccent)
                        }
                        onReady(url)
                        return false
                    }
                })
                .into(imageView)
        }

        attempt(0)
    }

    /**
     * SVG wordmarks via the registered SvgDecoder/SvgDrawableTranscoder — no size
     * ladder (single URL), PictureDrawable needs software layer off-TV.
     */
    private fun loadSvg(
        imageView: ImageView,
        logoUrl: String?,
        stillCurrent: () -> Boolean,
        hideUntilReady: Boolean,
        onAspect: ((Float) -> Unit)?,
        onFailed: () -> Unit,
        onReady: (readyUrl: String) -> Unit,
    ) {
        if (hideUntilReady) imageView.visibility = View.INVISIBLE
        val leanback = com.betterstreamflix.app.utils.DeviceCapabilities
            .isLeanbackDevice(imageView.context)
        imageView.setLayerType(
            if (leanback) View.LAYER_TYPE_NONE else View.LAYER_TYPE_SOFTWARE,
            null,
        )
        imageView.setTag(LOGO_URL_TAG, logoUrl)
        Glide.with(imageView)
            .`as`(android.graphics.drawable.PictureDrawable::class.java)
            .load(logoUrl)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .listener(object : RequestListener<android.graphics.drawable.PictureDrawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<android.graphics.drawable.PictureDrawable>,
                    isFirstResource: Boolean,
                ): Boolean {
                    if (!stillCurrent()) return false
                    if (imageView.getTag(LOGO_URL_TAG) != logoUrl) return false
                    TmdbLogoTelemetry.recordGlideFail()
                    classifyFailure(e)
                    if (TmdbLogoPicker.isTrustedTmdbLogo(logoUrl)) {
                        TmdbLogoCache.markDecodeFailed(logoUrl)
                    }
                    onFailed()
                    return true
                }

                override fun onResourceReady(
                    resource: android.graphics.drawable.PictureDrawable,
                    model: Any,
                    target: Target<android.graphics.drawable.PictureDrawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    if (!stillCurrent()) return false
                    if (imageView.getTag(LOGO_URL_TAG) != logoUrl) return false
                    if (resource.intrinsicWidth <= 1 || resource.intrinsicHeight <= 1) {
                        mainHandler.post { onFailed() }
                        return true
                    }
                    if (hideUntilReady) imageView.visibility = View.VISIBLE
                    onAspect?.invoke(
                        resource.intrinsicWidth.toFloat() / resource.intrinsicHeight,
                    )
                    onReady(logoUrl.orEmpty())
                    return false
                }
            })
            .into(imageView)
    }

    /** Classifies the last ladder failure for telemetry (404 vs corrupt vs other). */
    private fun classifyFailure(e: GlideException?) {
        val causes = e?.rootCauses.orEmpty().map { it.toString() }
        when {
            causes.any { "404" in it || "Not Found" in it } ->
                TmdbLogoTelemetry.recordGlideFailNotFound()
            causes.any {
                "decode" in it.lowercase() ||
                    "IOException" in it ||
                    "MarkException" in it ||
                    "corrupt" in it.lowercase()
            } -> TmdbLogoTelemetry.recordGlideFailCorrupt()
            e != null -> TmdbLogoTelemetry.recordGlideFailOther()
        }
    }

    private fun isMetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
