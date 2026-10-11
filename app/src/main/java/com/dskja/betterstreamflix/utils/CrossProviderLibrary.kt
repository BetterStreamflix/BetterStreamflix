package com.dskja.betterstreamflix.utils

import android.content.Context
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.WatchItem
import com.dskja.betterstreamflix.providers.Provider
import kotlinx.coroutines.flow.first

/**
 * Loads favorites / continue watching / recently watched either from the current
 * provider DB only, or aggregated across every provider DB on disk — controlled by
 * [UserPreferences.libraryScope].
 *
 * Items are stamped with [Movie.providerName] / [TvShow.providerName] so the UI can
 * switch provider before opening them.
 */
object CrossProviderLibrary {

    /** Global caps after cross-provider concat so rails stay browseable. */
    private const val GLOBAL_CW_CAP = 25
    private const val GLOBAL_NEXT_CAP = 25
    private const val GLOBAL_RW_CAP = 20

    data class HomeHistory(
        val continueWatching: List<AppAdapter.Item>,
        /** Next unwatched episode after the last finished one (separate Home shelf). */
        val nextEpisodes: List<Episode>,
        val recentlyWatched: List<AppAdapter.Item>,
        val favoriteMovies: List<Movie>,
        val favoriteTvShows: List<TvShow>,
    )

    suspend fun loadHomeHistory(context: Context): HomeHistory {
        val continueWatching = mutableListOf<AppAdapter.Item>()
        val nextEpisodes = mutableListOf<Episode>()
        val recentlyWatched = mutableListOf<AppAdapter.Item>()
        val favoriteMovies = mutableListOf<Movie>()
        val favoriteTvShows = mutableListOf<TvShow>()

        forEachScopedProviderDb(context) { provider, db ->
            // One corrupt / closed provider DB must not brick Home forever.
            try {
                favoriteMovies += db.movieDao().getFavorites().first()
                    .map { it.withProvider(provider.name) }
                favoriteTvShows += db.tvShowDao().getFavorites().first()
                    .map { it.withProvider(provider.name) }

                continueWatching += db.movieDao().getWatchingMoviesCapped().first()
                    .map { it.withProvider(provider.name) }

                val watchingEpisodes = db.episodeDao().getWatchingEpisodesCapped().first()
                val nextEpisodesRaw = db.episodeDao().getNextEpisodesToWatch().first()
                val allEpisodes = (watchingEpisodes + nextEpisodesRaw).distinctBy { it.id }
                // Prefer getByIds — getAll() on large libraries stalls the home combine.
                val tvShowIds = allEpisodes.mapNotNull { it.tvShow?.id }.distinct()
                val tvShowsMap = if (tvShowIds.isEmpty()) {
                    emptyMap()
                } else {
                    db.tvShowDao().getByIds(tvShowIds).first().associateBy { it.id }
                }
                val seasonIds = allEpisodes.mapNotNull { it.season?.id }.distinct()
                val seasonsMap = if (seasonIds.isEmpty()) {
                    emptyMap()
                } else {
                    db.seasonDao().getByIds(seasonIds).associateBy { it.id }
                }

                fun hydrate(episode: Episode): Episode = episode.copy(
                    tvShow = (episode.tvShow?.id?.let { tvShowsMap[it] } ?: episode.tvShow)
                        ?.withProvider(provider.name),
                    season = episode.season?.id?.let { seasonsMap[it] } ?: episode.season,
                ).apply { merge(episode) }

                continueWatching += watchingEpisodes.map(::hydrate)
                nextEpisodes += nextEpisodesRaw.map(::hydrate)

                recentlyWatched += db.movieDao().getRecentlyWatched().first()
                    .map { movie ->
                        movie.withProvider(provider.name).also { stamped ->
                            // Finished titles clear watchHistory; keep progress context for RW.
                            if (stamped.isWatched && stamped.watchHistory == null) {
                                stamped.watchHistory = WatchItem.WatchHistory(
                                    lastEngagementTimeUtcMillis = stamped.lastPlayedAtMillis ?: 0L,
                                    lastPlaybackPositionMillis = 1L,
                                    durationMillis = 1L,
                                )
                            }
                        }
                    }

                val recentTvShows = db.tvShowDao().getRecentlyWatched().first()
                val episodeIds = recentTvShows.mapNotNull { it.lastPlayedEpisodeId }.distinct()
                val episodesById = if (episodeIds.isEmpty()) {
                    emptyMap()
                } else {
                    db.episodeDao().getByIds(episodeIds).associateBy { it.id }
                }
                recentlyWatched += recentTvShows.map { tvShow ->
                    tvShow.withProvider(provider.name).apply {
                        lastPlayedEpisode = lastPlayedEpisodeId?.let(episodesById::get)?.also { ep ->
                            // Finished episodes clear watchHistory; keep a full-progress
                            // context so Recently Watched can still show a progress bar.
                            if (ep.isWatched && ep.watchHistory == null) {
                                ep.watchHistory = WatchItem.WatchHistory(
                                    lastEngagementTimeUtcMillis = lastPlayedAtMillis ?: 0L,
                                    lastPlaybackPositionMillis = 1L,
                                    durationMillis = 1L,
                                )
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Skip this provider DB and continue aggregating.
            }
        }

        return HomeHistory(
            continueWatching = continueWatching
                .sortedByDescending { engagementMillis(it) }
                .distinctBy { continueWatchingKey(it) }
                .take(GLOBAL_CW_CAP),
            nextEpisodes = nextEpisodes
                .sortedByDescending { engagementMillis(it) }
                .distinctBy { continueWatchingKey(it) }
                .take(GLOBAL_NEXT_CAP),
            recentlyWatched = recentlyWatched
                .sortedByDescending { recentlyWatchedMillis(it) }
                .distinctBy { libraryItemKey(it) }
                .take(GLOBAL_RW_CAP),
            favoriteMovies = favoriteMovies.sortedByDescending { it.favoritedAtMillis ?: 0L },
            favoriteTvShows = favoriteTvShows.sortedByDescending { it.favoritedAtMillis ?: 0L },
        )
    }

    suspend fun loadFavoriteMovies(context: Context): List<Movie> =
        loadHomeHistory(context).favoriteMovies

    suspend fun loadFavoriteTvShows(context: Context): List<TvShow> =
        loadHomeHistory(context).favoriteTvShows

    /** Clears Recently Watched across every DB in the active library scope. */
    suspend fun clearRecentlyWatched(context: Context) {
        forEachScopedProviderDb(context) { _, db ->
            runCatching {
                db.movieDao().clearAllRecentlyWatched()
                db.tvShowDao().clearAllRecentlyWatched()
            }
        }
    }

    private fun engagementMillis(item: AppAdapter.Item): Long = when (item) {
        is Movie -> item.watchHistory?.lastEngagementTimeUtcMillis
            ?: item.watchedDate?.timeInMillis
            ?: 0L
        is Episode -> item.watchHistory?.lastEngagementTimeUtcMillis
            ?: item.watchedDate?.timeInMillis
            ?: 0L
        else -> 0L
    }

    private fun recentlyWatchedMillis(item: AppAdapter.Item): Long = when (item) {
        is Movie -> item.lastPlayedAtMillis ?: 0L
        is TvShow -> item.lastPlayedAtMillis ?: 0L
        else -> 0L
    }

    private fun continueWatchingKey(item: AppAdapter.Item): String? = when (item) {
        is Episode -> {
            val showId = item.tvShow?.id
            if (showId.isNullOrBlank()) "episode:${item.id}"
            else "${item.tvShow?.providerName}:$showId"
        }
        is Movie -> "${item.providerName}:${item.id}"
        else -> null
    }

    private fun libraryItemKey(item: AppAdapter.Item): String? = when (item) {
        is Movie -> "${item.providerName}:${item.id}"
        is TvShow -> "${item.providerName}:${item.id}"
        else -> null
    }

    private inline fun forEachScopedProviderDb(
        context: Context,
        block: (Provider, AppDatabase) -> Unit,
    ) {
        val appContext = context.applicationContext
        val current = UserPreferences.currentProvider
        val currentName = current?.name
        val currentDb = if (currentName != null) {
            AppDatabase.getInstanceOrNull(appContext)
        } else {
            null
        }

        val providers = when (UserPreferences.libraryScope) {
            UserPreferences.LibraryScope.PER_PROVIDER -> listOfNotNull(current)
            UserPreferences.LibraryScope.CROSS_PROVIDER -> Provider.allKnown()
                .distinctBy { it.name }
                .filter { provider ->
                    appContext.getDatabasePath(AppDatabase.databaseNameFor(provider.name)).exists()
                }
        }

        providers.forEach { provider ->
            if (provider.name == currentName && currentDb != null) {
                block(provider, currentDb)
            } else {
                val db = AppDatabase.getInstanceForProvider(provider.name, appContext)
                try {
                    block(provider, db)
                } finally {
                    db.close()
                }
            }
        }
    }

    private fun Movie.withProvider(name: String): Movie = copy().apply {
        merge(this@withProvider)
        providerName = name
    }

    private fun TvShow.withProvider(name: String): TvShow = copy().apply {
        merge(this@withProvider)
        providerName = name
        isWatching = this@withProvider.isWatching
    }
}
