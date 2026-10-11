package com.dskja.betterstreamflix.watchlist

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.navigation.NavController
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.utils.UserDataCache
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-tap queue for Watchlist Digest:
 * 1) Seed Next Episodes (Room, no engagement) for each pick
 * 2) Start playback of the first episode
 *
 * Does not invent a fourth queue type — composes Room Next + player navigate.
 */
object WatchlistDigestQueue {
    private const val TAG = "WatchlistDigestQueue"

    data class Result(
        val seededCount: Int,
        val firstEpisode: Episode?,
    )

    suspend fun apply(
        context: Context,
        episodes: List<Episode>,
    ): Result = withContext(Dispatchers.IO) {
        if (episodes.isEmpty()) return@withContext Result(0, null)
        val seeded = seedNextEpisodes(context, episodes)
        WatchlistDigestPolicy.recordQueued()
        Result(seededCount = seeded, firstEpisode = episodes.firstOrNull())
    }

    fun seedNextEpisodes(context: Context, episodes: List<Episode>): Int {
        var count = 0
        val appContext = context.applicationContext
        for (episode in episodes) {
            val providerName = episode.tvShow?.providerName.orEmpty()
            val provider = Provider.findByName(providerName)
                ?: UserPreferences.currentProvider
                ?: continue
            val ok = runCatching {
                persistForNextShelf(appContext, provider, episode)
            }.getOrElse {
                Log.w(TAG, "seed failed for ${episode.id}", it)
                false
            }
            if (ok) count++
        }
        return count
    }

    fun navigateToEpisode(nav: NavController, context: Context, episode: Episode): Boolean {
        val providerName = episode.tvShow?.providerName
        if (!providerName.isNullOrBlank() &&
            providerName != UserPreferences.currentProvider?.name
        ) {
            Provider.findByName(providerName)?.let {
                UserPreferences.setCurrentProviderForPlayback(it)
            }
        }
        val subtitle = episode.season?.takeIf { it.number != 0 }?.let { season ->
            context.getString(
                R.string.player_subtitle_tv_show,
                season.number,
                episode.number,
                episode.title ?: context.getString(R.string.episode_number, episode.number),
            )
        } ?: context.getString(
            R.string.player_subtitle_tv_show_episode_only,
            episode.number,
            episode.title ?: context.getString(R.string.episode_number, episode.number),
        )
        val videoType = Video.Type.Episode(
            id = episode.id,
            number = episode.number,
            title = episode.title,
            poster = episode.poster,
            overview = episode.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = episode.tvShow?.id ?: "",
                title = episode.tvShow?.title ?: "",
                poster = episode.tvShow?.poster,
                banner = episode.tvShow?.banner,
                releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                imdbId = episode.tvShow?.imdbId,
            ),
            season = Video.Type.Episode.Season(
                number = episode.season?.number ?: 0,
                title = episode.season?.title,
            ),
        )
        val args = Bundle().apply {
            putString("id", episode.id)
            putString("title", episode.tvShow?.title ?: "")
            putString("subtitle", subtitle)
            putSerializable("videoType", videoType)
        }
        return runCatching {
            nav.navigate(R.id.action_global_player, args)
            true
        }.getOrElse {
            Log.w(TAG, "navigate to player failed", it)
            runCatching {
                nav.navigate(R.id.player, args)
                true
            }.getOrDefault(false)
        }
    }

    /**
     * Persist without WatchHistory/engagement so [EpisodeDao.getNextEpisodesToWatch]
     * can own the binge shelf (same contract as player queueNextEpisodeForContinueWatching).
     */
    private fun persistForNextShelf(
        context: Context,
        provider: Provider,
        episode: Episode,
    ): Boolean {
        val currentName = UserPreferences.currentProvider?.name
        val currentDb = AppDatabase.getInstanceOrNull(context)
        val ownsDb = provider.name != currentName || currentDb == null
        val db = if (!ownsDb) currentDb!! else AppDatabase.getInstanceForProvider(provider.name, context)
        try {
            if (!db.isOpen) return false
            val episodeDao = db.episodeDao()
            val existing = episodeDao.getById(episode.id)
            if (existing != null) {
                val hist = existing.watchHistory
                val fakeQueueEngagement = hist != null &&
                    hist.lastPlaybackPositionMillis == 0L &&
                    hist.durationMillis == 0L
                if (fakeQueueEngagement || existing.isWatched) {
                    existing.isWatched = false
                    existing.watchedDate = null
                    if (fakeQueueEngagement) existing.watchHistory = null
                    episodeDao.save(existing)
                    UserDataCache.syncEpisodeToCache(context, provider, existing)
                } else if (hist == null && !existing.isWatched) {
                    // Already eligible for Next — still count as seeded.
                } else {
                    // Has real engagement → leave on Continue Watching; still "queued" for UI.
                }
                return true
            }

            val tvShow = db.tvShowDao().getById(episode.tvShow?.id.orEmpty())
                ?: episode.tvShow
                ?: return false
            val season = episode.season ?: return false
            val persisted = Episode(
                id = episode.id,
                number = episode.number,
                title = episode.title,
                poster = episode.poster,
                overview = episode.overview,
                tvShow = TvShow(
                    id = tvShow.id,
                    title = tvShow.title,
                    poster = tvShow.poster,
                    banner = tvShow.banner,
                ).also { it.providerName = provider.name },
                season = Season(
                    number = season.number,
                    title = season.title,
                    id = season.id,
                ),
            ).apply {
                isWatched = false
                watchedDate = null
            }
            // Ensure show is watching so getNextEpisodesToWatch can surface it.
            runCatching {
                val row = db.tvShowDao().getById(tvShow.id)
                if (row != null && !row.isWatching && row.lastPlayedAtMillis == null) {
                    row.isWatching = true
                    db.tvShowDao().update(row)
                }
            }
            episodeDao.save(persisted)
            UserDataCache.syncEpisodeToCache(context, provider, persisted)
            return true
        } finally {
            if (ownsDb) runCatching { db.close() }
        }
    }
}
