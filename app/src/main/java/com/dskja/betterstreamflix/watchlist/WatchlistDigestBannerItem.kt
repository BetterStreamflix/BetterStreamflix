package com.dskja.betterstreamflix.watchlist

import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Episode

/**
 * Weekly home-row Watchlist Digest card: up to 5 new episodes from My List + Queue CTA.
 * Visibility is gated by [WatchlistDigestPolicy].
 */
data class WatchlistDigestBannerItem(
    val episodes: List<Episode>,
    val id: String = "watchlist_digest_banner",
) : AppAdapter.Item {
    override lateinit var itemType: AppAdapter.Type

    val episodeCount: Int get() = episodes.size
}
