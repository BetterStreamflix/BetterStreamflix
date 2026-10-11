package com.dskja.betterstreamflix.cast

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.utils.EpisodeManager
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.subtitleConfigurationsForPlayback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves the next episode stream and enqueues it on [CastPlaybackHub] for seamless Cast autoplay.
 */
object CastQueueCoordinator {
    private const val TAG = "CastQueue"
    /** Cap hoster hops when prefetching the next Cast item. */
    private const val MAX_SERVER_ATTEMPTS = 4

    data class NextCastItem(
        val mediaItem: MediaItem,
        val episode: Video.Type.Episode,
        val video: Video,
        val server: Video.Server,
    )

    /** Episode currently sitting in the Cast hub queue (not yet claimed). */
    @Volatile
    var pendingQueuedEpisode: Video.Type.Episode? = null
        private set

    /** Last episode claimed from the Cast queue (hub-only advance). */
    @Volatile
    var lastClaimedEpisode: Video.Type.Episode? = null
        private set

    /**
     * Prefetch next episode for Cast. Safe to call repeatedly — clears stale queue first.
     * Tries up to [MAX_SERVER_ATTEMPTS] hosters so a dead first server does not empty the queue.
     *
     * When [subtitleConfigurations] is null, builds configs via
     * [subtitleConfigurationsForPlayback] for the resolved next episode.
     */
    suspend fun prepareNextEpisodeQueue(
        context: Context,
        provider: Provider?,
        currentVideoType: Video.Type,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration>? = null,
        headers: Map<String, String> = emptyMap(),
    ): NextCastItem? = withContext(Dispatchers.IO) {
        lastFailureMessage = null
        if (currentVideoType !is Video.Type.Episode) return@withContext null
        if (!UserPreferences.autoplay && !UserPreferences.castQueueNextEpisode) return@withContext null
        val next = EpisodeManager.peekNextEpisode() ?: return@withContext null
        val activeProvider = provider ?: UserPreferences.currentProvider ?: return@withContext null
        runCatching {
            val nextType = Video.Type.Episode(
                id = next.id,
                number = next.number,
                title = next.title,
                poster = next.poster,
                overview = next.overview,
                tvShow = Video.Type.Episode.TvShow(
                    id = next.tvShow.id,
                    title = next.tvShow.title,
                    poster = next.tvShow.poster,
                    banner = next.tvShow.banner,
                    releaseDate = next.tvShow.releaseDate,
                    imdbId = next.tvShow.imdbId,
                ),
                season = Video.Type.Episode.Season(
                    number = next.season.number,
                    title = next.season.title,
                ),
            )
            val servers = activeProvider.getServers(next.id, nextType)
            if (servers.isEmpty()) {
                lastFailureMessage = "no servers"
                return@runCatching null
            }
            var resolved: Pair<Video.Server, Video>? = null
            var lastError: String? = null
            for (server in servers.take(MAX_SERVER_ATTEMPTS)) {
                try {
                    val video = activeProvider.getVideo(server)
                    if (video.source.isNotBlank()) {
                        resolved = server to video
                        break
                    }
                    lastError = "empty source (${server.name})"
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    Log.w(TAG, "Cast next server ${server.name} failed: ${e.message}")
                }
            }
            val (server, video) = resolved ?: run {
                lastFailureMessage = lastError ?: "all servers failed"
                return@runCatching null
            }
            val title = next.tvShow.title
            val subtitle = "S${next.season.number} E${next.number}" +
                (next.title?.takeIf { it.isNotBlank() }?.let { "  •  $it" } ?: "")
            val metadata = CastMediaFactory.buildMetadata(
                title = title,
                subtitle = subtitle,
                videoType = nextType,
                serverId = server.id,
            )
            // Prefer the next episode's resolved headers; fall back to caller override
            // only when the provider returned none (CAST-P1-2).
            val mergedHeaders = video.headers.orEmpty().ifEmpty { headers }
            val configs = subtitleConfigurations
                ?: subtitleConfigurationsForPlayback(
                    context = context.applicationContext,
                    videoType = nextType,
                    serverSubtitles = video.subtitles,
                )
            val item = CastMediaFactory.queueItemForCast(
                source = video.source,
                mimeType = video.type,
                headers = mergedHeaders,
                metadata = metadata,
                subtitleConfigurations = configs,
            )
            NextCastItem(mediaItem = item, episode = nextType, video = video, server = server)
        }.onFailure {
            lastFailureMessage = it.message ?: "unknown"
            Log.w(TAG, "Cast next enqueue failed: ${it.message}")
        }.getOrNull()?.also {
            lastFailureMessage = null
        }
    }

    /** Last prepare failure message, cleared on success. */
    @Volatile
    var lastFailureMessage: String? = null
        private set

    fun enqueueExclusive(item: MediaItem, episode: Video.Type.Episode? = null) {
        CastPlaybackHub.clearQueue()
        CastPlaybackHub.enqueue(item)
        pendingQueuedEpisode = episode
        Log.i(TAG, "Cast queue size=${CastPlaybackHub.queuedCount()}")
    }

    /**
     * Called when the Cast hub claims the next queued item. Advances [EpisodeManager]
     * so local autoplay / UI stay aligned with the episode Cast is already playing.
     */
    fun onQueueItemClaimed(): Video.Type.Episode? {
        val episode = pendingQueuedEpisode ?: return null
        pendingQueuedEpisode = null
        lastClaimedEpisode = episode
        val peeked = EpisodeManager.peekNextEpisode()
        if (peeked != null && peeked.id == episode.id) {
            EpisodeManager.getNextEpisode()
        } else {
            EpisodeManager.setCurrentEpisode(episode)
        }
        return episode
    }
}
