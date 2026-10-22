package com.betterstreamflix.app.download

import android.content.Context
import android.util.Log
import com.betterstreamflix.app.R
import com.betterstreamflix.app.models.Episode
import com.betterstreamflix.app.models.Season
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.models.Video
import com.betterstreamflix.app.utils.UserPreferences
import org.json.JSONObject

/**
 * Netflix-style Smart Downloads (opt-in):
 *  - `downloadAutoDeleteWatched`: remove a finished episode's download.
 *  - `downloadSmartEnabled`: silently enqueue the next episodes (lookahead).
 *  - Wi‑Fi only, per-series quota, and low-storage pause are enforced.
 *
 * Invoked from the players' watch-finished paths. All provider/network work is
 * best-effort — failures just skip the smart action, never crash playback.
 */
object SmartDownloadsManager {
    private const val TAG = "SmartDownloads"

    /** In-memory debounce so repeated pause/stop events don't re-trigger work. */
    private val handled = mutableSetOf<String>()

    /**
     * After enabling Smart in Studio Controls — best-effort lookahead from the
     * latest completed episode per series on the active profile.
     */
    suspend fun sweepAfterEnable(context: Context) {
        if (!UserPreferences.downloadSmartEnabled) return
        val repo = DownloadRepository.get(context)
        val completed = repo.getAllOnce()
            .filter { DownloadProfileScope.matchesActive(it.profileId) }
            .filter { it.kind == DownloadKind.EPISODE.name }
            .filter { it.state == DownloadItemState.COMPLETED.name }
        val bySeries = completed.groupBy { SmartDownloadsPolicy.seriesKeyOf(it).orEmpty() }
            .filterKeys { it.isNotBlank() }
        for ((_, eps) in bySeries) {
            val latest = eps.maxByOrNull { it.updatedAt } ?: continue
            val videoType = DownloadController.deserializeVideoType(latest.videoTypeJson)
                as? Video.Type.Episode
                ?: continue
            runCatching {
                enqueueNextEpisodes(context, videoType, latest.providerName)
            }.onFailure { Log.w(TAG, "sweep failed for ${latest.id}: ${it.message}") }
        }
    }

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
        if (smart) enqueueNextEpisodes(context, videoType, item?.providerName)
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

    private suspend fun enqueueNextEpisodes(
        context: Context,
        current: Video.Type.Episode,
        finishedProviderName: String?,
    ) {
        // Prefer the finished item's provider, not whatever is currently selected (DL-P2-L).
        val provider = finishedProviderName
            ?.takeIf { it.isNotBlank() }
            ?.let { com.betterstreamflix.app.providers.Provider.findByName(it) }
            ?: UserPreferences.currentProvider
            ?: return
        if (provider is com.betterstreamflix.app.providers.IptvProvider) return
        if (DownloadConnectivityMonitor.shouldHoldForWifiOnly(context)) return
        if (DownloadStorage.isLowSpace(context) || !DownloadStorage.hasEnoughSpace(context)) return
        if (DownloadConnectivityMonitor.current(context).type == DownloadNetworkType.NONE) return

        val lookahead = SmartDownloadsPolicy.clampLookahead(UserPreferences.downloadSmartLookahead)
        val quota = SmartDownloadsPolicy.clampSeriesQuota(UserPreferences.downloadSmartSeriesQuota)
        val seriesKey = SmartDownloadsPolicy.seriesKeyForShow(provider.name, current.tvShow.id)

        val previousProvider = UserPreferences.currentProvider
        if (previousProvider?.name != provider.name) {
            UserPreferences.setCurrentProviderForPlayback(provider)
        }
        val upcoming = runCatching {
            findNextEpisodes(current, provider, lookahead)
        }.getOrElse { emptyList() }
        if (previousProvider != null && previousProvider.name != provider.name) {
            UserPreferences.setCurrentProviderForPlayback(previousProvider)
        }
        if (upcoming.isEmpty()) return

        val repo = DownloadRepository.get(context)
        val allItems = repo.getAllOnce().filter { DownloadProfileScope.matchesActive(it.profileId) }
        val occupancy = SmartDownloadsPolicy.seriesOccupancy(allItems, seriesKey)

        val missing = upcoming.filter { next ->
            val existing = allItems.firstOrNull { entity ->
                entity.kind == DownloadKind.EPISODE.name &&
                    videoTypeIdEquals(entity.videoTypeJson, next.id)
            }
            existing == null || existing.state == DownloadItemState.FAILED.name
        }
        val budget = SmartDownloadsPolicy.enqueueBudget(
            missingUpcoming = missing.size,
            occupancy = occupancy,
            lookahead = lookahead,
            quota = quota,
        )
        if (budget <= 0) {
            Log.i(TAG, "smart quota/lookahead exhausted for $seriesKey (occupancy=$occupancy)")
            return
        }

        if (previousProvider?.name != provider.name) {
            UserPreferences.setCurrentProviderForPlayback(provider)
        }
        try {
            var enqueued = 0
            for (next in missing) {
                if (enqueued >= budget) break
                if (DownloadStorage.isLowSpace(context) || !DownloadStorage.hasEnoughSpace(context)) {
                    Log.i(TAG, "smart stop: storage low after $enqueued")
                    break
                }
                if (DownloadConnectivityMonitor.shouldHoldForWifiOnly(context)) {
                    Log.i(TAG, "smart stop: Wi‑Fi hold after $enqueued")
                    break
                }
                if (enqueueOne(context, current, next)) {
                    enqueued++
                }
            }
            if (enqueued > 0) {
                Log.i(TAG, "smart-downloaded $enqueued episode(s) for $seriesKey")
                DownloadLaneScheduler.apply(context)
            }
        } finally {
            if (previousProvider != null && previousProvider.name != provider.name) {
                UserPreferences.setCurrentProviderForPlayback(previousProvider)
            }
        }
    }

    private suspend fun enqueueOne(
        context: Context,
        current: Video.Type.Episode,
        next: NextEpisode,
    ): Boolean {
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
        return when (prepared) {
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
                    Log.i(TAG, "smart-downloaded S${next.seasonNumber}E${next.number}")
                    true
                } else {
                    false
                }
            }
            is DownloadEnqueueOutcome.Started -> {
                DownloadRepository.get(context).upsert(
                    prepared.item.copy(smartEnqueued = true),
                )
                Log.i(TAG, "smart-downloaded S${next.seasonNumber}E${next.number}")
                true
            }
            else -> false
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
     * Next [count] episodes after [current], crossing season boundaries.
     * Returns fewer than [count] at the end of a show.
     */
    private suspend fun findNextEpisodes(
        current: Video.Type.Episode,
        provider: com.betterstreamflix.app.providers.Provider,
        count: Int,
    ): List<NextEpisode> {
        if (count <= 0) return emptyList()
        val show = provider.getTvShow(current.tvShow.id)
        val seasons = show.seasons.orEmpty().sortedBy { it.number }
        if (seasons.isEmpty()) return emptyList()

        val result = ArrayList<NextEpisode>(count)
        var seasonIdx = seasons.indexOfFirst { it.number == current.season.number }
        if (seasonIdx < 0) return emptyList()

        var afterNumber = current.number
        while (result.size < count && seasonIdx < seasons.size) {
            val season = seasons[seasonIdx]
            val episodes = runCatching { provider.getEpisodesBySeason(season.id) }
                .getOrNull()
                .orEmpty()
                .sortedBy { it.number }
            val candidates = if (season.number == current.season.number && result.isEmpty()) {
                episodes.filter { it.number > afterNumber }
            } else {
                episodes
            }
            for (ep in candidates) {
                result.add(
                    NextEpisode(
                        id = ep.id,
                        number = ep.number,
                        seasonNumber = season.number,
                        title = ep.title,
                        poster = ep.poster,
                        overview = ep.overview,
                        seasonTitle = season.title,
                    ),
                )
                if (result.size >= count) break
            }
            seasonIdx++
            afterNumber = Int.MIN_VALUE
        }
        return result
    }

    private fun videoTypeIdEquals(videoTypeJson: String, id: String): Boolean {
        if (videoTypeJson.isBlank() || id.isBlank()) return false
        return runCatching {
            JSONObject(videoTypeJson).optString("id") == id
        }.getOrDefault(false)
    }
}
