package com.dskja.betterstreamflix.platform

import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow

/**
 * Merges local Continue Watching with optional Trakt / self-host rows without duplicates.
 */
object ContinueWatchingMerger {
    private val SELFHOST_CW_PREFIXES = listOf(
        "Jellyfin · Continue",
        "Plex · On Deck",
        Category.CONTINUE_WATCHING,
    )

    fun isProviderContinueWatching(name: String): Boolean =
        SELFHOST_CW_PREFIXES.any { name.equals(it, ignoreCase = true) } ||
            (
                name.contains("continue", ignoreCase = true) &&
                    (
                        name.contains("jellyfin", ignoreCase = true) ||
                            name.contains("plex", ignoreCase = true) ||
                            name.contains("on deck", ignoreCase = true)
                        )
                )

    /**
     * Builds a single CW list: merge by identity, then re-sort by last engagement
     * so remote Trakt/Jellyfin/Plex rows can surface ahead of stale locals.
     */
    fun merge(
        local: List<AppAdapter.Item>,
        remoteExtras: List<AppAdapter.Item>,
    ): List<AppAdapter.Item> {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<AppAdapter.Item>()
        fun addAll(items: List<AppAdapter.Item>) {
            for (item in items) {
                val key = identityKey(item) ?: continue
                if (seen.add(key)) out.add(item)
            }
        }
        addAll(local)
        addAll(remoteExtras)
        return out.sortedByDescending { engagementMillis(it) }
    }

    fun engagementMillis(item: AppAdapter.Item): Long = when (item) {
        is Movie -> item.watchHistory?.lastEngagementTimeUtcMillis
            ?: item.watchedDate?.timeInMillis
            ?: 0L
        is Episode -> item.watchHistory?.lastEngagementTimeUtcMillis
            ?: item.watchedDate?.timeInMillis
            ?: 0L
        is TvShow -> item.lastPlayedAtMillis ?: 0L
        else -> 0L
    }

    fun identityKey(item: AppAdapter.Item): String? = when (item) {
        is Movie -> {
            val provider = item.providerName?.takeIf { it.isNotBlank() } ?: "_"
            val imdb = item.imdbId?.takeIf { it.isNotBlank() }
            "movie:$provider:${imdb ?: item.id}"
        }
        is Episode -> {
            val show = item.tvShow
            val provider = show?.providerName?.takeIf { it.isNotBlank() } ?: "_"
            val showKey = show?.imdbId?.takeIf { it.isNotBlank() } ?: show?.id
            // Keep orphan episodes (hydrate miss) instead of dropping them from CW.
            if (showKey != null) "ep:$provider:$showKey" else "ep:id:${item.id}"
        }
        is TvShow -> {
            val provider = item.providerName?.takeIf { it.isNotBlank() } ?: "_"
            val imdb = item.imdbId?.takeIf { it.isNotBlank() }
            "show:$provider:${imdb ?: item.id}"
        }
        else -> null
    }

    /** True when [candidate] shares show/movie identity with any item in [active]. */
    fun overlaps(candidate: AppAdapter.Item, active: Collection<AppAdapter.Item>): Boolean {
        val key = identityKey(candidate) ?: return false
        return active.any { identityKey(it) == key }
    }

    /** Strip provider CW categories that HomeViewModel already merged into the unified row. */
    fun filterProviderCategories(categories: List<Category>): List<Category> =
        categories.filterNot { isProviderContinueWatching(it.name) }
}
