package com.betterstreamflix.app.widgets

import android.content.Context
import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.models.Episode
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.WatchItem
import com.betterstreamflix.app.tvlauncher.TvWatchDeepLink
import com.betterstreamflix.app.utils.CrossProviderLibrary
import com.betterstreamflix.app.utils.LiveHubCatalog
import com.betterstreamflix.app.utils.UserDataCache
import com.betterstreamflix.app.utils.UserDataCache.toEpisode
import com.betterstreamflix.app.utils.UserDataCache.toMovie
import com.betterstreamflix.app.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Snapshot rows for home-screen App Widgets. */
object WidgetLibrary {

    data class ContinueRow(
        val kind: String,
        val id: String,
        val provider: String,
        val title: String,
        val subtitle: String,
        val poster: String?,
        val progressPercent: Int,
        val deepLink: String,
    )

    data class LiveFavoriteRow(
        val id: String,
        val provider: String,
        val title: String,
        val poster: String?,
        val deepLink: String,
    )

    suspend fun loadContinueWatching(context: Context, limit: Int = 3): List<ContinueRow> =
        withContext(Dispatchers.IO) {
            val items = runCatching {
                CrossProviderLibrary.loadHomeHistory(context).continueWatching
            }.getOrElse { emptyList() }.ifEmpty {
                loadContinueWatchingFromCache(context)
            }
            items.take(limit).mapNotNull { toContinueRow(it) }
        }

    suspend fun loadLiveFavorites(context: Context, limit: Int = 4): List<LiveFavoriteRow> =
        withContext(Dispatchers.IO) {
            // EPG-008 / kids: do not build live tiles under age-gate / Kids Fortress.
            if (LiveHubCatalog.isAgeGated() ||
                !com.betterstreamflix.app.kids.KidsFortress.liveHubAllowed()
            ) {
                return@withContext emptyList()
            }
            val favorites = runCatching {
                LiveHubCatalog.resolveFavoriteChannels(context, hubShows = emptyList())
            }.getOrElse { emptyList() }
            favorites
                .distinctBy { "${it.providerName}:${it.id}" }
                .take(limit)
                .mapNotNull { show ->
                    val provider = show.providerName.orEmpty()
                    if (provider.isBlank()) return@mapNotNull null
                    LiveFavoriteRow(
                        id = show.id,
                        provider = provider,
                        title = show.title,
                        poster = show.poster,
                        deepLink = WidgetDeepLinks.buildLiveChannelUri(
                            id = show.id,
                            provider = provider,
                            title = show.title,
                            poster = show.poster,
                        ),
                    )
                }
        }

    private fun loadContinueWatchingFromCache(context: Context): List<AppAdapter.Item> {
        val provider = UserPreferences.currentProvider ?: return emptyList()
        val data = UserDataCache.read(context, provider) ?: return emptyList()
        val movies = data.continueWatchingMovies.map {
            it.toMovie().apply { providerName = provider.name }
        }
        val episodes = data.continueWatchingEpisodes.map {
            it.toEpisode().apply {
                tvShow?.providerName = provider.name
            }
        }
        return (movies + episodes).sortedByDescending { engagementMillis(it) }
    }

    private fun toContinueRow(item: AppAdapter.Item): ContinueRow? {
        return when (item) {
            is Movie -> {
                val provider = item.providerName.orEmpty()
                if (provider.isBlank()) return null
                val progress = progressPercent(item.watchHistory)
                ContinueRow(
                    kind = TvWatchDeepLink.Kind.MOVIE.wire,
                    id = item.id,
                    provider = provider,
                    title = item.title,
                    subtitle = "",
                    poster = item.poster ?: item.banner,
                    progressPercent = progress,
                    deepLink = TvWatchDeepLink.buildUri(
                        TvWatchDeepLink.Kind.MOVIE,
                        item.id,
                        provider,
                    ),
                )
            }
            is Episode -> {
                val provider = item.tvShow?.providerName.orEmpty()
                if (provider.isBlank()) return null
                val showTitle = item.tvShow?.title.orEmpty().ifBlank { item.title.orEmpty() }
                val progress = progressPercent(item.watchHistory)
                val subtitle = buildString {
                    item.season?.number?.takeIf { it > 0 }?.let { append("S$it ") }
                    append("E${item.number}")
                    item.title?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                }
                ContinueRow(
                    kind = TvWatchDeepLink.Kind.EPISODE.wire,
                    id = item.id,
                    provider = provider,
                    title = showTitle,
                    subtitle = subtitle,
                    poster = item.poster ?: item.tvShow?.poster,
                    progressPercent = progress,
                    deepLink = TvWatchDeepLink.buildUri(
                        TvWatchDeepLink.Kind.EPISODE,
                        item.id,
                        provider,
                    ),
                )
            }
            else -> null
        }
    }

    private fun progressPercent(history: WatchItem.WatchHistory?): Int {
        val duration = history?.durationMillis ?: 0L
        val position = history?.lastPlaybackPositionMillis ?: 0L
        if (duration <= 0L || position <= 0L) return 0
        return ((position * 100L) / duration).toInt().coerceIn(0, 100)
    }

    private fun engagementMillis(item: AppAdapter.Item): Long = when (item) {
        is Movie -> item.watchHistory?.lastEngagementTimeUtcMillis ?: 0L
        is Episode -> item.watchHistory?.lastEngagementTimeUtcMillis ?: 0L
        else -> 0L
    }
}
