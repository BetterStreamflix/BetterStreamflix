package com.dskja.betterstreamflix.home

import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.UserPreferences

/**
 * Home shelf filter “only new since last visit” for series rows
 * (Next Episodes + Favorite TV shows).
 *
 * Visit watermark: on Home pause we snapshot shelf ids + timestamp; on the next
 * resume the filter keeps items that were not in that snapshot (or aired after).
 * Resume never advances the watermark — pause commits it.
 */
object SeriesFastForward {

    fun supportsShelf(categoryName: String): Boolean =
        categoryName == Category.NEXT_EPISODES ||
            categoryName == Category.FAVORITE_TV_SHOWS

    fun isEnabled(): Boolean = UserPreferences.seriesFastForwardEnabled

    fun setEnabled(enabled: Boolean) {
        UserPreferences.seriesFastForwardEnabled = enabled
    }

    fun lastVisitAtMs(): Long = UserPreferences.homeLastVisitAtMs

    /**
     * Commit the end of a Home visit. Pass **unfiltered** shelf ids so a later
     * Fast-Forward pass can detect titles that appeared after this pause.
     */
    fun commitVisit(
        nextEpisodeIds: Set<String>,
        favoriteTvIds: Set<String>,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        UserPreferences.homeLastVisitAtMs = nowMs.coerceAtLeast(0L)
        UserPreferences.homeVisitNextEpisodeIds = nextEpisodeIds
        UserPreferences.homeVisitFavoriteTvIds = favoriteTvIds
    }

    fun filterEpisodes(
        episodes: List<Episode>,
        sinceMs: Long = lastVisitAtMs(),
        previousIds: Set<String> = UserPreferences.homeVisitNextEpisodeIds,
    ): List<Episode> {
        if (!isEnabled()) return episodes
        if (!hasVisitBaseline(sinceMs, previousIds)) return episodes
        return episodes.filter { episode ->
            isNewEpisode(episode, sinceMs, previousIds)
        }
    }

    fun filterTvShows(
        shows: List<TvShow>,
        sinceMs: Long = lastVisitAtMs(),
        previousIds: Set<String> = UserPreferences.homeVisitFavoriteTvIds,
    ): List<TvShow> {
        if (!isEnabled()) return shows
        if (!hasVisitBaseline(sinceMs, previousIds)) return shows
        return shows.filter { show ->
            isNewTvShow(show, sinceMs, previousIds)
        }
    }

    fun isNewEpisode(
        episode: Episode,
        sinceMs: Long,
        previousIds: Set<String>,
    ): Boolean {
        if (episode.id.isNotBlank() && episode.id !in previousIds) return true
        val releasedMs = episode.released?.timeInMillis
        if (releasedMs != null && releasedMs > sinceMs) return true
        return false
    }

    fun isNewTvShow(
        show: TvShow,
        sinceMs: Long,
        previousIds: Set<String>,
    ): Boolean {
        if (show.id.isNotBlank() && show.id !in previousIds) return true
        val favoritedAt = show.favoritedAtMillis
        if (favoritedAt != null && favoritedAt > sinceMs) return true
        val releasedMs = show.released?.timeInMillis
        if (releasedMs != null && releasedMs > sinceMs) return true
        return false
    }

    /** True once a prior Home pause wrote a watermark (and ideally a snapshot). */
    fun hasVisitBaseline(sinceMs: Long, previousIds: Set<String>): Boolean =
        sinceMs > 0L || previousIds.isNotEmpty()

    fun keepShelfWhenEmpty(
        categoryName: String,
        unfilteredCount: Int,
        filteredCount: Int,
    ): Boolean {
        if (unfilteredCount <= 0) return false
        if (filteredCount > 0) return true
        return isEnabled() && supportsShelf(categoryName)
    }

    fun applyToLibraryItem(
        item: AppAdapter.Item,
        sinceMs: Long,
        previousNextIds: Set<String>,
        previousFavIds: Set<String>,
    ): Boolean = when (item) {
        is Episode -> isNewEpisode(item, sinceMs, previousNextIds)
        is TvShow -> isNewTvShow(item, sinceMs, previousFavIds)
        else -> true
    }
}
