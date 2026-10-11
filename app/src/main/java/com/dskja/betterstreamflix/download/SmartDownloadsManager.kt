package com.dskja.betterstreamflix.download

import android.content.Context
import android.util.Log
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.utils.UserPreferences
import org.json.JSONObject

/**
 * Netflix-style Smart Downloads (opt-in):
 *  - `downloadAutoDeleteWatched`: remove a finished episode's download.
 *  - `downloadSmartEnabled`:     silently enqueue the next episode.
 *
 * Invoked from the players' watch-finished paths. All provider/network work is
 * best-effort — failures just skip the smart action, never crash playback.
 */
object SmartDownloadsManager {
    private const val TAG = "SmartDownloads"

    /** In-memory debounce so repeated pause/stop events don't re-trigger work. */
    private val handled = mutableSetOf<String>()

    suspend fun onEpisodeFinished(
        context: Context,
        videoType: Video.Type.Episode,
        positionMs: Long = -1L,
        durationMs: Long = -1L,
        playbackEnded: Boolean = false,
    ) {
        if (!DownloadWatchPolicy.shouldMarkWatched(positionMs, durationMs, playbackEnded)) return
        val key = "${videoType.tvShow.id}:${videoType.season.number}:${videoType.number}:${videoType.id}"
        if (!handled.add(key)) return
        if (handled.size > 64) handled.clear() // bound the debounce set

        val repo = DownloadRepository.get(context)
        val item = repo.getAllOnce().firstOrNull { entity ->
            DownloadProfileScope.matchesActive(entity.profileId) &&
                entity.kind == DownloadKind.EPISODE.name &&
                videoTypeIdEquals(entity.videoTypeJson, videoType.id)
        }

        // Always mark watched for Downloads UI — smart prefs only gate delete/enqueue.
        if (item != null && !item.watchedOffline) {
            repo.upsert(item.copy(watchedOffline = true, updatedAt = System.currentTimeMillis()))
        }

        val autoDelete = UserPreferences.downloadAutoDeleteWatched
        val smart = UserPreferences.downloadSmartEnabled
        if (autoDelete && item?.state == DownloadItemState.COMPLETED.name) {
            repo.remove(item.id)
        }
        if (smart) enqueueNextEpisode(context, videoType)
    }

    suspend fun onMovieFinished(
        context: Context,
        videoType: Video.Type.Movie,
        positionMs: Long = -1L,
        durationMs: Long = -1L,
        playbackEnded: Boolean = false,
    ) {
        if (!DownloadWatchPolicy.shouldMarkWatched(positionMs, durationMs, playbackEnded)) return
        val key = "movie:${videoType.id}"
        if (!handled.add(key)) return
        if (handled.size > 64) handled.clear()

        val repo = DownloadRepository.get(context)
        val item = repo.getAllOnce().firstOrNull { entity ->
            DownloadProfileScope.matchesActive(entity.profileId) &&
                entity.kind == DownloadKind.MOVIE.name &&
                videoTypeIdEquals(entity.videoTypeJson, videoType.id)
        } ?: return

        if (!item.watchedOffline) {
            repo.upsert(item.copy(watchedOffline = true, updatedAt = System.currentTimeMillis()))
        }
        if (UserPreferences.downloadAutoDeleteWatched &&
            item.state == DownloadItemState.COMPLETED.name
        ) {
            repo.remove(item.id)
        }
    }

    private suspend fun enqueueNextEpisode(context: Context, current: Video.Type.Episode) {
        val provider = UserPreferences.currentProvider ?: return
        if (provider is com.dskja.betterstreamflix.providers.IptvProvider) return
        if (DownloadConnectivityMonitor.shouldHoldForWifiOnly(context)) return
        if (!DownloadStorage.hasEnoughSpace(context)) return
        if (DownloadConnectivityMonitor.current(context).type == DownloadNetworkType.NONE) return

        val next = runCatching { findNextEpisode(context, current) }.getOrNull() ?: return

        // Skip if the next episode is already downloaded or in flight.
        val existing = DownloadRepository.get(context).getAllOnce().firstOrNull { entity ->
            DownloadProfileScope.matchesActive(entity.profileId) &&
                entity.kind == DownloadKind.EPISODE.name &&
                videoTypeIdEquals(entity.videoTypeJson, next.id)
        }
        if (existing != null && existing.state != DownloadItemState.FAILED.name) return

        val episode = Episode(
            id = next.id,
            number = next.number,
            title = next.title,
            poster = next.poster,
            overview = next.overview,
            tvShow = TvShow(
                id = current.tvShow.id,
                title = current.tvShow.title,
                poster = current.tvShow.poster,
                banner = current.tvShow.banner,
                imdbId = current.tvShow.imdbId,
            ),
            season = Season(
                id = "",
                number = next.seasonNumber,
                title = next.seasonTitle,
            ),
        )

        val prepared = DownloadController.prepareEpisode(context, episode)
        when (prepared) {
            is DownloadEnqueueOutcome.NeedsOptions -> {
                val trackIdx = when (UserPreferences.downloadQualityPreset) {
                    DownloadQualityPreset.DATA_SAVER ->
                        prepared.prepared.trackOptions.lastIndex.coerceAtLeast(0)
                    else -> 0
                }.coerceIn(0, prepared.prepared.trackOptions.lastIndex.coerceAtLeast(0))
                val label = prepared.prepared.trackOptions.getOrNull(trackIdx)?.label
                    ?: context.getString(R.string.download_quality_auto)
                val confirmed = DownloadController.confirmEnqueue(
                    context = context,
                    prepared = prepared.prepared,
                    serverIndex = prepared.prepared.selectedServerIndex,
                    trackIndex = trackIdx,
                    qualityLabel = label,
                    smartEnqueued = true,
                )
                if (confirmed is DownloadEnqueueOutcome.Started) {
                    Log.i(TAG, "smart-downloaded next episode S${next.seasonNumber}E${next.number}")
                }
            }
            is DownloadEnqueueOutcome.Started -> {
                DownloadRepository.get(context).upsert(
                    prepared.item.copy(smartEnqueued = true),
                )
                Log.i(TAG, "smart-downloaded next episode S${next.seasonNumber}E${next.number}")
            }
            else -> Unit
        }
    }

    private data class NextEpisode(
        val id: String,
        val number: Int,
        val seasonNumber: Int,
        val title: String?,
        val poster: String?,
        val overview: String?,
        val seasonTitle: String?,
    )

    /**
     * Next episode in the same season; if the current one was the last, the
     * first episode of the next season. Returns null at the end of a show.
     */
    private suspend fun findNextEpisode(
        context: Context,
        current: Video.Type.Episode,
    ): NextEpisode? {
        val provider = UserPreferences.currentProvider ?: return null
        val show = provider.getTvShow(current.tvShow.id)
        val seasons = show.seasons.orEmpty().sortedBy { it.number }
        if (seasons.isEmpty()) return null

        val currentSeason = seasons.firstOrNull { it.number == current.season.number }
            ?: return null
        val episodes = runCatching { provider.getEpisodesBySeason(currentSeason.id) }
            .getOrNull()
            .orEmpty()
            .sortedBy { it.number }

        episodes.firstOrNull { it.number == current.number + 1 }?.let { ep ->
            return NextEpisode(
                id = ep.id,
                number = ep.number,
                seasonNumber = current.season.number,
                title = ep.title,
                poster = ep.poster,
                overview = ep.overview,
                seasonTitle = currentSeason.title,
            )
        }

        // Season boundary: first episode of the next season.
        val nextSeason = seasons.firstOrNull { it.number > current.season.number } ?: return null
        val first = runCatching { provider.getEpisodesBySeason(nextSeason.id) }
            .getOrNull()
            .orEmpty()
            .minByOrNull { it.number }
            ?: return null
        return NextEpisode(
            id = first.id,
            number = first.number,
            seasonNumber = nextSeason.number,
            title = first.title,
            poster = first.poster,
            overview = first.overview,
            seasonTitle = nextSeason.title,
        )
    }

    private fun videoTypeIdEquals(videoTypeJson: String, id: String): Boolean {
        if (videoTypeJson.isBlank() || id.isBlank()) return false
        return runCatching {
            JSONObject(videoTypeJson).optString("id") == id
        }.getOrDefault(false)
    }
}
