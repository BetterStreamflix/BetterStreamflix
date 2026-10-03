package com.dskja.betterstreamflix.ui

import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.ArtworkUrls

/**
 * Pure helpers for the TV Featured swiper: index wrap, coercion, and banner
 * selection. Kept view-free so unit tests can cover rotation without Robolectric.
 */
object FeaturedTvRotation {

    fun coerceIndex(selectedIndex: Int, size: Int): Int {
        if (size <= 0) return 0
        return selectedIndex.coerceIn(0, size - 1)
    }

    /** Advance one step (wraps). No-op when [size] ≤ 1. */
    fun nextIndex(selectedIndex: Int, size: Int): Int {
        if (size <= 1) return coerceIndex(selectedIndex, size)
        return (coerceIndex(selectedIndex, size) + 1) % size
    }

    /** Full-quality Featured banner (TMDb original when applicable). */
    fun bannerOf(item: AppAdapter.Item?): String? = when (item) {
        is Movie -> ArtworkUrls.featuredBannerOrPoster(item.banner, item.poster)
        is TvShow -> ArtworkUrls.featuredBannerOrPoster(item.banner, item.poster)
        else -> null
    }

    fun titleOf(item: AppAdapter.Item?): String? = when (item) {
        is Movie -> item.title
        is TvShow -> item.title
        else -> null
    }
}
