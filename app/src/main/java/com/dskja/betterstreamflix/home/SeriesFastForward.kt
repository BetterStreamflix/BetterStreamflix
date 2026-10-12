package com.dskja.betterstreamflix.home

import android.content.Context
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.fragments.search.searchIdentityKey
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.taste.TasteGraph
import com.dskja.betterstreamflix.taste.TasteSnapshot
import com.dskja.betterstreamflix.utils.UserPreferences

/**
 * Formerly “only new since last visit” for Next Episodes / Favorite TV.
 * Feature removed from the product — methods stay as no-ops / pass-through
 * so call sites and tests compile without reintroducing the chip.
 */
object SeriesFastForward {

    /** English stamp / identityKey for Next Episodes + Favorite TV. */
    fun supportsShelf(categoryName: String): Boolean = false

    fun supportsShelf(category: Category): Boolean = false

    fun isEnabled(): Boolean = false

    fun setEnabled(enabled: Boolean) {
        // Feature removed — clear any leftover profile flag.
        UserPreferences.seriesFastForwardEnabled = false
    }

    fun lastVisitAtMs(): Long = 0L

    fun commitVisit(
        nextEpisodeIds: Set<String>,
        favoriteTvIds: Set<String>,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        // no-op — visit watermark retired with the Fast-Forward chip
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

    /**
     * True once a prior Home pause wrote a watermark **with** shelf ids.
     * Timestamp-only commits are ignored so first-session novelty is correct.
     */
    fun hasVisitBaseline(sinceMs: Long, previousIds: Set<String>): Boolean =
        previousIds.isNotEmpty() && sinceMs > 0L

    fun keepShelfWhenEmpty(
        categoryName: String,
        unfilteredCount: Int,
        filteredCount: Int,
    ): Boolean {
        if (unfilteredCount <= 0) return false
        if (filteredCount > 0) return true
        return isEnabled() && supportsShelf(categoryName)
    }

    fun keepShelfWhenEmpty(
        category: Category,
        unfilteredCount: Int,
        filteredCount: Int,
    ): Boolean {
        if (unfilteredCount <= 0) return false
        if (filteredCount > 0) return true
        return isEnabled() && supportsShelf(category)
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

    /** Drop Taste hard-hidden titles from Home FF shelves (and Favorites TV). */
    fun <T : AppAdapter.Item> filterTasteHidden(
        context: Context,
        items: List<T>,
    ): List<T> {
        val snap = TasteGraph.snapshot(context.applicationContext)
        if (snap.hardHidden.isEmpty()) return items
        return items.filterNot { item -> isTasteHardHidden(item, snap) }
    }

    fun isTasteHardHidden(item: AppAdapter.Item, snap: TasteSnapshot): Boolean {
        val key = item.searchIdentityKey()
        val tmdb = when (item) {
            is Movie -> TasteSnapshot.tmdbKey(item.tmdbId, isTv = false)
            is TvShow -> TasteSnapshot.tmdbKey(item.tmdbId, isTv = true)
            is Episode -> TasteSnapshot.tmdbKey(item.tvShow?.tmdbId, isTv = true)
            else -> null
        }
        // Episodes: also check parent show identity when present.
        val showKey = (item as? Episode)?.tvShow?.id?.takeIf { it.isNotBlank() }?.let { "tvshow:$it" }
        return snap.isHardHidden(key, tmdb) ||
            (showKey != null && snap.isHardHidden(showKey, null))
    }
}
