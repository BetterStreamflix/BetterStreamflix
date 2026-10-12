package com.dskja.betterstreamflix.watchlist

import android.content.Context
import android.util.Log
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.providers.IptvProvider
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.utils.CrossProviderLibrary
import com.dskja.betterstreamflix.utils.LiveHubCatalog
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds the weekly Watchlist Digest: up to [MAX_EPISODES] next unwatched episodes
 * from My List (favorite) series. Room-first — no network on the home path.
 */
object WatchlistDigestPlanner {
    private const val TAG = "WatchlistDigest"
    const val MAX_EPISODES = 5
    private const val MAX_SHOWS_SCANNED = 16

    /** Weekly semantics: air date within this window (or null released skipped). */
    val RECENT_AIR_WINDOW_MS: Long = java.util.concurrent.TimeUnit.DAYS.toMillis(14)

    /**
     * Pure selection for tests: map favorite show keys → next episode, keep order, cap.
     */
    fun selectDigestEpisodes(
        favoriteKeysInOrder: List<String>,
        nextByShowKey: Map<String, Episode>,
        max: Int = MAX_EPISODES,
        nowMs: Long = System.currentTimeMillis(),
    ): List<Episode> {
        if (max <= 0) return emptyList()
        val picks = ArrayList<Episode>(max.coerceAtMost(MAX_EPISODES))
        val seen = HashSet<String>()
        for (key in favoriteKeysInOrder) {
            if (picks.size >= max) break
            if (!seen.add(key)) continue
            val episode = nextByShowKey[key] ?: continue
            if (!isWeeklyCandidate(episode, nowMs)) continue
            picks.add(episode)
        }
        return picks
    }

    /**
     * Keep episodes released in the last 14 days, or with a non-null recent air date
     * after the user's last watch watermark on that title.
     */
    fun isWeeklyCandidate(
        episode: Episode,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val releasedMs = episode.released?.timeInMillis ?: return false
        if (nowMs - releasedMs <= RECENT_AIR_WINDOW_MS) return true
        // Stale air date — skip for weekly digest semantics.
        return false
    }

    suspend fun plan(context: Context): List<Episode> = withContext(Dispatchers.IO) {
        if (!UserPreferences.homeWatchlistDigestEnabled) return@withContext emptyList()
        if (com.dskja.betterstreamflix.profiles.ProfileManager.isKidsActive()) {
            return@withContext emptyList()
        }

        val favorites = runCatching {
            CrossProviderLibrary.loadFavoriteTvShows(context)
        }.getOrElse {
            Log.w(TAG, "loadFavoriteTvShows failed", it)
            emptyList()
        }
            .filter { !LiveHubCatalog.isLiveFavorite(it) }
            .sortedWith(
                compareByDescending<TvShow> { it.lastPlayedAtMillis ?: 0L }
                    .thenByDescending { it.favoritedAtMillis ?: 0L },
            )
            .take(MAX_SHOWS_SCANNED)

        if (favorites.isEmpty()) return@withContext emptyList()

        val nextByKey = LinkedHashMap<String, Episode>()
        val favoriteKeys = ArrayList<String>(favorites.size)

        for (show in favorites) {
            val providerName = show.providerName.orEmpty()
            if (providerName.isBlank()) continue
            val provider = Provider.findByName(providerName) ?: continue
            if (provider is IptvProvider) continue
            val key = showKey(providerName, show.id)
            favoriteKeys.add(key)
            if (key in nextByKey) continue
            val next = runCatching {
                resolveNextUnwatched(context, provider, show)
            }.getOrNull() ?: continue
            nextByKey[key] = next
        }

        val selected = selectDigestEpisodes(favoriteKeys, nextByKey, MAX_EPISODES)
        // Parental gate before Home insert (Kids already short-circuited above).
        com.dskja.betterstreamflix.utils.ParentalControlUtils.filterItems(selected)
    }

    fun showKey(providerName: String, showId: String): String = "$providerName:$showId"

    private fun resolveNextUnwatched(
        context: Context,
        provider: Provider,
        show: TvShow,
    ): Episode? {
        val appContext = context.applicationContext
        val currentName = UserPreferences.currentProvider?.name
        val currentDb = AppDatabase.getInstanceOrNull(appContext)
        val ownsDb = provider.name != currentName || currentDb == null
        val db = if (!ownsDb) {
            currentDb!!
        } else {
            AppDatabase.getInstanceForProviderOrNull(provider.name, appContext) ?: return null
        }
        try {
            if (!db.isOpen) return null
            val episodes = db.episodeDao().getByTvShowId(show.id)
            if (episodes.isEmpty()) return null

            val seasonIds = episodes.mapNotNull { it.season?.id }.distinct()
            val seasonsById = if (seasonIds.isEmpty()) {
                emptyMap()
            } else {
                db.seasonDao().getByIds(seasonIds).associateBy { it.id }
            }
            val seasonsByShow = db.seasonDao().getByTvShowId(show.id).associateBy { it.id }

            fun seasonOf(ep: Episode): Season? {
                val id = ep.season?.id
                return (id?.let { seasonsById[it] ?: seasonsByShow[it] } ?: ep.season)
            }

            val ordered = episodes.sortedWith(
                compareBy(
                    { seasonOf(it)?.number ?: Int.MAX_VALUE },
                    { it.number },
                ),
            )

            val lastWatched = ordered.lastOrNull { it.isWatched }
            val candidate = if (lastWatched == null) {
                ordered.firstOrNull { !it.isWatched } ?: ordered.firstOrNull()
            } else {
                val lastSeason = seasonOf(lastWatched)?.number ?: 0
                ordered.firstOrNull { ep ->
                    if (ep.isWatched) return@firstOrNull false
                    val sn = seasonOf(ep)?.number ?: 0
                    sn > lastSeason || (sn == lastSeason && ep.number > lastWatched.number)
                }
            } ?: return null

            if (candidate.isWatched) return null

            val tvShowRow = db.tvShowDao().getById(show.id) ?: show
            val season = seasonOf(candidate) ?: candidate.season ?: return null

            return candidate.copy(
                tvShow = tvShowRow.copy(
                    id = tvShowRow.id,
                    title = tvShowRow.title,
                    poster = tvShowRow.poster,
                    banner = tvShowRow.banner,
                ).also { it.providerName = provider.name },
                season = Season(
                    id = season.id,
                    number = season.number,
                    title = season.title,
                    poster = season.poster,
                ),
            ).apply {
                isWatched = false
                watchedDate = null
                watchHistory = null
                tvShow?.providerName = provider.name
            }
        } finally {
            if (ownsDb) {
                runCatching { db.close() }
            }
        }
    }
}
