package com.dskja.betterstreamflix.ui

import android.content.Context
import android.widget.ImageView

/**
 * Compatibility facade — logo Glide loading lives in
 * [com.dskja.betterstreamflix.logo.TmdbLogoGlide].
 */
object TmdbLogoGlide {

    fun clear(imageView: ImageView) =
        com.dskja.betterstreamflix.logo.TmdbLogoGlide.clear(imageView)

    fun prefetch(context: Context, logoUrl: String?, wifiOnly: Boolean = true) =
        com.dskja.betterstreamflix.logo.TmdbLogoGlide.prefetch(context, logoUrl, wifiOnly)

    fun load(
        imageView: ImageView,
        logoUrl: String?,
        stillCurrent: () -> Boolean = { true },
        hideUntilReady: Boolean = false,
        contentDescription: String? = null,
        onFailed: () -> Unit,
        onReady: (readyUrl: String) -> Unit,
    ) = com.dskja.betterstreamflix.logo.TmdbLogoGlide.load(
        imageView = imageView,
        logoUrl = logoUrl,
        stillCurrent = stillCurrent,
        hideUntilReady = hideUntilReady,
        contentDescription = contentDescription,
        onFailed = onFailed,
        onReady = onReady,
    )

    /** Legacy signature used by older call sites (no hideUntilReady). */
    fun load(
        imageView: ImageView,
        logoUrl: String?,
        stillCurrent: () -> Boolean = { true },
        onFailed: () -> Unit,
        onReady: (readyUrl: String) -> Unit,
    ) = load(
        imageView = imageView,
        logoUrl = logoUrl,
        stillCurrent = stillCurrent,
        hideUntilReady = false,
        contentDescription = null,
        onFailed = onFailed,
        onReady = onReady,
    )
}
