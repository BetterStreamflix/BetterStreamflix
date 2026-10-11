package com.dskja.betterstreamflix.download

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.exoplayer.offline.DownloadHelper
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.providers.IptvProvider
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DownloadPrepareResult(
    val contentKey: String,
    val providerName: String,
    val kind: DownloadKind,
    val title: String,
    val subtitle: String,
    val posterUrl: String,
    val videoType: Video.Type,
    val videoTypeJson: String,
    val servers: List<ResolvedServerCandidate>,
    val selectedServerIndex: Int,
    val trackOptions: List<DownloadTrackOption>,
)

data class ResolvedServerCandidate(
    val server: Video.Server,
    val video: Video,
)

data class DownloadTrackOption(
    val label: String,
    val streamKeys: List<StreamKey>,
    val estimatedBytes: Long = -1L,
)

sealed class DownloadEnqueueOutcome {
    data class AlreadyCompleted(val item: DownloadItemEntity) : DownloadEnqueueOutcome()
    data class AlreadyActive(val item: DownloadItemEntity) : DownloadEnqueueOutcome()
    data class NeedsOptions(val prepared: DownloadPrepareResult) : DownloadEnqueueOutcome()
    data class Started(val item: DownloadItemEntity) : DownloadEnqueueOutcome()
    data class Failed(val code: DownloadErrorCode, val message: String) : DownloadEnqueueOutcome()
}

object DownloadController {
    private const val TAG = "DownloadController"
    private const val MAX_OPTION_SERVERS = 8
    private const val RESOLVE_PARALLELISM = 3
    private const val MIN_RESOLVED_CANDIDATES = 3
    private val helperExecutor = Executors.newSingleThreadExecutor()

    suspend fun prepareMovie(context: Context, movie: Movie): DownloadEnqueueOutcome =
        withContext(Dispatchers.IO) {
            val provider = UserPreferences.currentProvider
                ?: return@withContext DownloadEnqueueOutcome.Failed(
                    DownloadErrorCode.UNKNOWN,
                    context.getString(R.string.download_error_no_provider),
                )
            if (provider is IptvProvider) {
                return@withContext DownloadEnqueueOutcome.Failed(
                    DownloadErrorCode.IPTV,
                    context.getString(R.string.download_error_iptv),
                )
            }
            val contentKey = DownloadContentKey.movie(provider.name, movie.id)
            checkExisting(context, contentKey)?.let { return@withContext it }
            // Skip Wi‑Fi hold here so NeedsOptions can offer a cellular confirm (DL-P1-B).
            guardNetworkAndStorage(context, skipWifi = true)?.let { return@withContext it }

            val videoType = Video.Type.Movie(
                id = movie.id,
                title = movie.title,
                releaseDate = movie.released?.format("yyyy-MM-dd") ?: "",
                poster = movie.poster ?: "",
                imdbId = movie.imdbId,
            )
            resolveAndPrepare(
                context = context,
                provider = provider,
                contentKey = contentKey,
                kind = DownloadKind.MOVIE,
                title = movie.title,
                subtitle = provider.name,
                posterUrl = movie.poster.orEmpty(),
                videoType = videoType,
                contentId = movie.id,
            )
        }

    suspend fun prepareEpisode(context: Context, episode: Episode): DownloadEnqueueOutcome =
        withContext(Dispatchers.IO) {
            val provider = UserPreferences.currentProvider
                ?: return@withContext DownloadEnqueueOutcome.Failed(
                    DownloadErrorCode.UNKNOWN,
                    context.getString(R.string.download_error_no_provider),
                )
            if (provider is IptvProvider) {
                return@withContext DownloadEnqueueOutcome.Failed(
                    DownloadErrorCode.IPTV,
                    context.getString(R.string.download_error_iptv),
                )
            }
            val tvShow = episode.tvShow
                ?: return@withContext DownloadEnqueueOutcome.Failed(
                    DownloadErrorCode.UNKNOWN,
                    context.getString(R.string.download_error_missing_show),
                )
            val seasonNumber = episode.season?.number ?: 1
            val contentKey = DownloadContentKey.episode(
                providerName = provider.name,
                tvShowId = tvShow.id,
                seasonNumber = seasonNumber,
                episodeNumber = episode.number,
                episodeId = episode.id,
            )
            checkExisting(context, contentKey)?.let { return@withContext it }
            // Skip Wi‑Fi hold here so NeedsOptions can offer a cellular confirm (DL-P1-B).
            guardNetworkAndStorage(context, skipWifi = true)?.let { return@withContext it }

            val videoType = Video.Type.Episode(
                id = episode.id,
                number = episode.number,
                title = episode.title,
                poster = episode.poster,
                overview = episode.overview,
                tvShow = Video.Type.Episode.TvShow(
                    id = tvShow.id,
                    title = tvShow.title,
                    poster = tvShow.poster,
                    banner = tvShow.banner,
                    releaseDate = tvShow.released?.format("yyyy-MM-dd"),
                    imdbId = null,
                ),
                season = Video.Type.Episode.Season(
                    number = seasonNumber,
                    title = episode.season?.title,
                ),
            )
            resolveAndPrepare(
                context = context,
                provider = provider,
                contentKey = contentKey,
                kind = DownloadKind.EPISODE,
                title = tvShow.title,
                subtitle = "S${seasonNumber.toString().padStart(2, '0')}E${episode.number.toString().padStart(2, '0')}" +
                    (episode.title?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                posterUrl = episode.poster ?: tvShow.poster.orEmpty(),
                videoType = videoType,
                contentId = episode.id,
            )
        }

    suspend fun prepareFromResolved(
        context: Context,
        videoType: Video.Type,
        server: Video.Server,
        video: Video,
    ): DownloadEnqueueOutcome = withContext(Dispatchers.IO) {
        val provider = UserPreferences.currentProvider
            ?: return@withContext DownloadEnqueueOutcome.Failed(
                DownloadErrorCode.UNKNOWN,
                context.getString(R.string.download_error_no_provider),
            )
        if (provider is IptvProvider) {
            return@withContext DownloadEnqueueOutcome.Failed(
                DownloadErrorCode.IPTV,
                context.getString(R.string.download_error_iptv),
            )
        }
        // Skip Wi‑Fi hold here so NeedsOptions can offer a cellular confirm (DL-P1-B).
        guardNetworkAndStorage(context, skipWifi = true)?.let { return@withContext it }

        val contentKey: String
        val kind: DownloadKind
        val title: String
        val subtitle: String
        val poster: String
        when (videoType) {
            is Video.Type.Movie -> {
                contentKey = DownloadContentKey.movie(provider.name, videoType.id)
                kind = DownloadKind.MOVIE
                title = videoType.title
                subtitle = provider.name
                poster = videoType.poster
            }
            is Video.Type.Episode -> {
                contentKey = DownloadContentKey.episode(
                    provider.name,
                    videoType.tvShow.id,
                    videoType.season.number,
                    videoType.number,
                    videoType.id,
                )
                kind = DownloadKind.EPISODE
                title = videoType.tvShow.title
                subtitle = "S${videoType.season.number}E${videoType.number}"
                poster = videoType.poster ?: videoType.tvShow.poster.orEmpty()
            }
        }
        checkExisting(context, contentKey)?.let { return@withContext it }

        if (isUnsupportedSource(video.source)) {
            return@withContext DownloadEnqueueOutcome.Failed(
                DownloadErrorCode.UNSUPPORTED,
                "Unsupported stream format",
            )
        }
        if (looksLikeDrm(video.source)) {
            return@withContext DownloadEnqueueOutcome.Failed(DownloadErrorCode.DRM, "Protected stream")
        }

        val preparedTracks = prepareTrackOptions(context, video)
        DownloadEnqueueOutcome.NeedsOptions(
            DownloadPrepareResult(
                contentKey = contentKey,
                providerName = provider.name,
                kind = kind,
                title = title,
                subtitle = subtitle,
                posterUrl = poster,
                videoType = videoType,
                videoTypeJson = serializeVideoType(videoType),
                servers = listOf(ResolvedServerCandidate(server, video)),
                selectedServerIndex = 0,
                trackOptions = preparedTracks,
            ),
        )
    }

    suspend fun confirmEnqueue(
        context: Context,
        prepared: DownloadPrepareResult,
        serverIndex: Int,
        trackIndex: Int,
        qualityLabel: String,
        smartEnqueued: Boolean = false,
        allowMetered: Boolean = false,
    ): DownloadEnqueueOutcome = withContext(Dispatchers.IO) {
        guardNetworkAndStorage(context, allowMetered = allowMetered)?.let { return@withContext it }
        val candidate = prepared.servers.getOrNull(serverIndex)
            ?: prepared.servers.firstOrNull()
            ?: return@withContext DownloadEnqueueOutcome.Failed(
                DownloadErrorCode.NO_SERVERS,
                context.getString(R.string.download_error_no_server),
            )
        val track = prepared.trackOptions.getOrNull(trackIndex)
        val autoLabel = context.getString(R.string.download_quality_auto)
        val item = DownloadRepository.get(context).enqueueResolved(
            contentKey = prepared.contentKey,
            providerName = prepared.providerName,
            kind = prepared.kind,
            title = prepared.title,
            subtitle = prepared.subtitle,
            posterUrl = prepared.posterUrl,
            videoTypeJson = prepared.videoTypeJson,
            serverName = candidate.server.name,
            serverId = candidate.server.id,
            qualityLabel = qualityLabel.ifBlank { track?.label ?: autoLabel },
            mimeType = candidate.video.type,
            streamUrl = candidate.video.source,
            headers = candidate.video.headers.orEmpty(),
            streamKeys = track?.streamKeys.orEmpty(),
            subtitleUrls = candidate.video.subtitles.map { it.label to it.file },
            smartEnqueued = smartEnqueued,
        )
        DownloadEnqueueOutcome.Started(item)
    }

    suspend fun enqueueSeason(
        context: Context,
        tvShow: TvShow,
        seasonNumber: Int,
        episodes: List<Episode>,
        allowMetered: Boolean = false,
        onEachPrepared: suspend (DownloadPrepareResult) -> Pair<Int, Int>,
    ): DownloadEnqueueOutcome = withContext(Dispatchers.IO) {
        val provider = UserPreferences.currentProvider
            ?: return@withContext DownloadEnqueueOutcome.Failed(
                DownloadErrorCode.UNKNOWN,
                context.getString(R.string.download_error_no_provider),
            )
        if (provider is IptvProvider) {
            return@withContext DownloadEnqueueOutcome.Failed(
                DownloadErrorCode.IPTV,
                context.getString(R.string.download_error_iptv),
            )
        }
        // Skip Wi‑Fi hold; UI confirms cellular before season enqueue when needed.
        guardNetworkAndStorage(context, skipWifi = true)?.let { return@withContext it }
        if (episodes.isEmpty()) {
            return@withContext DownloadEnqueueOutcome.Failed(
                DownloadErrorCode.UNKNOWN,
                context.getString(R.string.download_error_no_episodes),
            )
        }

        val packId = DownloadContentKey.seasonPack(provider.name, tvShow.id, seasonNumber)
        val repo = DownloadRepository.get(context)
        repo.upsertSeasonPack(
            DownloadSeasonPackEntity(
                id = packId,
                providerName = provider.name,
                tvShowId = tvShow.id,
                tvShowTitle = tvShow.title,
                seasonNumber = seasonNumber,
                posterUrl = tvShow.poster.orEmpty(),
                totalEpisodes = episodes.size,
            ),
        )

        var started = 0
        episodes.forEachIndexed { index, episode ->
            episode.tvShow = tvShow
            val outcome = prepareEpisode(context, episode)
            when (outcome) {
                is DownloadEnqueueOutcome.NeedsOptions -> {
                    val (rawServerIdx, rawTrackIdx) = onEachPrepared(outcome.prepared)
                    val serverIdx = rawServerIdx.coerceIn(
                        0,
                        outcome.prepared.servers.lastIndex.coerceAtLeast(0),
                    )
                    val trackIdx = rawTrackIdx.coerceIn(
                        0,
                        outcome.prepared.trackOptions.lastIndex.coerceAtLeast(0),
                    )
                    val label = outcome.prepared.trackOptions.getOrNull(trackIdx)?.label
                        ?: context.getString(R.string.download_quality_auto)
                    val confirmed = confirmEnqueue(
                        context,
                        outcome.prepared,
                        serverIdx,
                        trackIdx,
                        label,
                        allowMetered = allowMetered,
                    )
                    if (confirmed is DownloadEnqueueOutcome.Started) {
                        repo.assignPack(confirmed.item.id, packId, index)
                        started++
                    } else {
                        Log.w(TAG, "Season NeedsOptions confirm failed for ${episode.id}: $confirmed")
                    }
                }
                is DownloadEnqueueOutcome.Started -> {
                    repo.assignPack(outcome.item.id, packId, index)
                    started++
                }
                is DownloadEnqueueOutcome.AlreadyActive -> {
                    repo.assignPack(outcome.item.id, packId, index)
                    started++
                }
                is DownloadEnqueueOutcome.AlreadyCompleted -> {
                    repo.assignPack(outcome.item.id, packId, index)
                    started++
                }
                is DownloadEnqueueOutcome.Failed -> Log.w(TAG, "Season item failed: ${outcome.message}")
            }
        }
        repo.refreshSeasonPack(packId)
        if (started == 0) {
            DownloadEnqueueOutcome.Failed(
                DownloadErrorCode.NO_SERVERS,
                context.getString(R.string.download_error_no_episodes_queued),
            )
        } else {
            val pack = repo.getSeasonPack(packId)
            DownloadEnqueueOutcome.Started(
                DownloadItemEntity(
                    id = packId,
                    contentKey = packId,
                    media3Id = packId,
                    providerName = provider.name,
                    kind = DownloadKind.EPISODE.name,
                    title = tvShow.title,
                    subtitle = context.getString(
                        R.string.download_season_pack_subtitle,
                        seasonNumber,
                        started,
                        episodes.size,
                    ),
                    posterUrl = tvShow.poster.orEmpty(),
                    seasonPackId = packId,
                ),
            ).also { pack }
        }
    }

    private suspend fun resolveAndPrepare(
        context: Context,
        provider: Provider,
        contentKey: String,
        kind: DownloadKind,
        title: String,
        subtitle: String,
        posterUrl: String,
        videoType: Video.Type,
        contentId: String,
    ): DownloadEnqueueOutcome {
        val servers = try {
            provider.getServers(contentId, videoType)
        } catch (e: Exception) {
            Log.e(TAG, "getServers failed", e)
            return classifyFailure(context, e)
        }
        if (servers.isEmpty()) {
            return DownloadEnqueueOutcome.Failed(
                DownloadErrorCode.NO_SERVERS,
                context.getString(R.string.download_error_no_servers),
            )
        }

        val resolved = mutableListOf<ResolvedServerCandidate>()
        var lastError: Exception? = null
        val errorMutex = Mutex()
        val resolvedMutex = Mutex()
        // Resolve several servers in parallel so the options dialog can switch hosters.
        coroutineScope {
            val semaphore = Semaphore(RESOLVE_PARALLELISM)
            val jobs = servers.take(MAX_OPTION_SERVERS).map { server ->
                async {
                    semaphore.withPermit {
                        resolvedMutex.withLock {
                            if (resolved.size >= MIN_RESOLVED_CANDIDATES) return@withPermit
                        }
                        try {
                            val video = provider.getVideo(server)
                            if (video.source.isBlank()) return@withPermit
                            if (isUnsupportedSource(video.source)) return@withPermit
                            if (looksLikeDrm(video.source)) return@withPermit
                            resolvedMutex.withLock {
                                if (resolved.size < MIN_RESOLVED_CANDIDATES) {
                                    resolved += ResolvedServerCandidate(server, video)
                                }
                            }
                        } catch (e: Exception) {
                            errorMutex.withLock { lastError = e }
                            Log.w(TAG, "getVideo failed for ${server.name}: ${e.message}")
                        }
                    }
                }
            }
            jobs.awaitAll()
        }

        if (resolved.isEmpty()) {
            return lastError?.let { classifyFailure(context, it) }
                ?: DownloadEnqueueOutcome.Failed(
                    DownloadErrorCode.NO_SERVERS,
                    context.getString(R.string.download_error_all_servers_failed),
                )
        }

        val tracks = prepareTrackOptions(context, resolved.first().video)
        val prepared = DownloadPrepareResult(
            contentKey = contentKey,
            providerName = provider.name,
            kind = kind,
            title = title,
            subtitle = subtitle,
            posterUrl = posterUrl,
            videoType = videoType,
            videoTypeJson = serializeVideoType(videoType),
            servers = resolved,
            selectedServerIndex = 0,
            trackOptions = tracks,
        )

        // Always show options so users can pick server + quality before starting.
        return DownloadEnqueueOutcome.NeedsOptions(prepared)
    }

    /**
     * Re-resolve and re-enqueue a failed/paused item (fresh servers + headers).
     * Auto-confirms quality using the user's preset when options would be shown.
     */
    suspend fun retryItem(context: Context, entity: DownloadItemEntity): DownloadEnqueueOutcome =
        withContext(Dispatchers.IO) {
            val videoType = deserializeVideoType(entity.videoTypeJson)
                ?: return@withContext DownloadEnqueueOutcome.Failed(
                    DownloadErrorCode.UNKNOWN,
                    context.getString(R.string.download_error_missing_metadata),
                )
            DownloadRepository.get(context).remove(entity.id)
            val prepared = when (videoType) {
                is Video.Type.Movie -> prepareMovie(
                    context,
                    Movie(
                        id = videoType.id,
                        title = videoType.title,
                        poster = videoType.poster,
                        imdbId = videoType.imdbId,
                    ),
                )
                is Video.Type.Episode -> prepareEpisode(
                    context,
                    Episode(
                        id = videoType.id,
                        number = videoType.number,
                        title = videoType.title,
                        poster = videoType.poster,
                        overview = videoType.overview,
                        tvShow = TvShow(
                            id = videoType.tvShow.id,
                            title = videoType.tvShow.title,
                            poster = videoType.tvShow.poster,
                            banner = videoType.tvShow.banner,
                            imdbId = videoType.tvShow.imdbId,
                        ),
                        season = Season(
                            id = "",
                            number = videoType.season.number,
                            title = videoType.season.title,
                        ),
                    ),
                )
            }
            when (prepared) {
                is DownloadEnqueueOutcome.NeedsOptions -> {
                    val trackIdx = when (UserPreferences.downloadQualityPreset) {
                        DownloadQualityPreset.DATA_SAVER ->
                            prepared.prepared.trackOptions.lastIndex.coerceAtLeast(0)
                        else -> 0
                    }.coerceIn(0, prepared.prepared.trackOptions.lastIndex.coerceAtLeast(0))
                    confirmEnqueue(
                        context,
                        prepared.prepared,
                        prepared.prepared.selectedServerIndex,
                        trackIdx,
                        prepared.prepared.trackOptions.getOrNull(trackIdx)?.label
                            ?: context.getString(R.string.download_quality_auto),
                    )
                }
                else -> prepared
            }
        }

    /** Returns how many failed items were successfully re-queued. */
    suspend fun retryAllFailed(context: Context): Int = withContext(Dispatchers.IO) {
        val failed = DownloadRepository.get(context).getAllOnce()
            .filter { it.state == DownloadItemState.FAILED.name }
        var started = 0
        failed.forEach { entity ->
            when (retryItem(context, entity)) {
                is DownloadEnqueueOutcome.Started,
                is DownloadEnqueueOutcome.AlreadyActive,
                is DownloadEnqueueOutcome.AlreadyCompleted,
                -> started++
                else -> Unit
            }
        }
        started
    }

    suspend fun prepareTrackOptions(context: Context, video: Video): List<DownloadTrackOption> {
        return try {
            val helper = createHelper(context, video)
            prepareHelper(helper)
            val refs = mutableListOf<DownloadTrackPlan.TrackRef>()
            val periodCount = helper.periodCount
            for (periodIndex in 0 until periodCount) {
                val tracks = helper.getTracks(periodIndex)
                tracks.groups.forEachIndexed { groupIndex, group ->
                    val interesting = group.type == androidx.media3.common.C.TRACK_TYPE_VIDEO ||
                        group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO ||
                        group.type == androidx.media3.common.C.TRACK_TYPE_TEXT
                    if (!interesting) return@forEachIndexed
                    for (i in 0 until group.length) {
                        val height = if (group.type == androidx.media3.common.C.TRACK_TYPE_VIDEO) {
                            group.getTrackFormat(i).height
                        } else {
                            0
                        }
                        refs += DownloadTrackPlan.TrackRef(
                            type = group.type,
                            periodIndex = periodIndex,
                            groupIndex = groupIndex,
                            trackIndex = i,
                            height = height,
                        )
                    }
                }
            }
            helper.release()
            DownloadTrackPlan.build(
                tracks = refs,
                bestLabel = context.getString(R.string.download_quality_best),
                dataSaverLabel = context.getString(R.string.download_quality_data_saver),
            )
        } catch (e: Exception) {
            Log.w(TAG, "prepareTracks failed: ${e.message}")
            listOf(
                DownloadTrackOption(context.getString(R.string.download_quality_auto), emptyList()),
            )
        }
    }

    private fun createHelper(context: Context, video: Video): DownloadHelper {
        val factory = StreamflixDownloadManager.dataSourceFactory(context)
        factory.activeHeaders = video.headers.orEmpty()
        val mime = video.type ?: when {
            video.source.contains(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
            video.source.contains(".mpd", true) -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        val mediaItem = MediaItem.Builder()
            .setUri(video.source)
            .setMimeType(mime)
            .build()
        return DownloadHelper.forMediaItem(
            context,
            mediaItem,
            null,
            factory,
        )
    }

    private suspend fun prepareHelper(helper: DownloadHelper) =
        suspendCancellableCoroutine { cont ->
            helper.prepare(object : DownloadHelper.Callback {
                override fun onPrepared(helper: DownloadHelper, tracksInfoAvailable: Boolean) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onPrepareError(helper: DownloadHelper, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            })
            cont.invokeOnCancellation {
                runCatching { helper.release() }
            }
        }

    private suspend fun checkExisting(
        context: Context,
        contentKey: String,
    ): DownloadEnqueueOutcome? {
        val existing = DownloadRepository.get(context).getByContentKey(contentKey) ?: return null
        return when (DownloadItemState.fromKey(existing.state)) {
            DownloadItemState.COMPLETED -> DownloadEnqueueOutcome.AlreadyCompleted(existing)
            DownloadItemState.QUEUED,
            DownloadItemState.PREPARING,
            DownloadItemState.DOWNLOADING,
            DownloadItemState.PAUSED,
            -> DownloadEnqueueOutcome.AlreadyActive(existing)
            else -> null
        }
    }

    private fun guardNetworkAndStorage(
        context: Context,
        allowMetered: Boolean = false,
        skipWifi: Boolean = false,
    ): DownloadEnqueueOutcome.Failed? {
        DownloadStorage.storageUnavailableReason(context)?.let { reasonRes ->
            return DownloadEnqueueOutcome.Failed(
                DownloadErrorCode.NOSPACE,
                context.getString(reasonRes),
            )
        }
        if (!DownloadStorage.hasEnoughSpace(context)) {
            return DownloadEnqueueOutcome.Failed(
                DownloadErrorCode.NOSPACE,
                context.getString(R.string.download_error_nospace),
            )
        }
        if (DownloadStorage.isOverSoftLimit(context)) {
            return DownloadEnqueueOutcome.Failed(
                DownloadErrorCode.NOSPACE,
                context.getString(R.string.download_error_soft_limit),
            )
        }
        if (!skipWifi && !allowMetered &&
            DownloadConnectivityMonitor.shouldHoldForWifiOnly(context)
        ) {
            return DownloadEnqueueOutcome.Failed(
                DownloadErrorCode.WIFI_REQUIRED,
                context.getString(R.string.download_error_wifi),
            )
        }
        val net = DownloadConnectivityMonitor.current(context)
        if (net.type == DownloadNetworkType.NONE) {
            return DownloadEnqueueOutcome.Failed(
                DownloadErrorCode.NETWORK,
                context.getString(R.string.download_error_network),
            )
        }
        return null
    }

    private fun isUnsupportedSource(source: String): Boolean {
        if (source.isBlank()) return true
        if (source.startsWith("data:", ignoreCase = true)) return true
        if (!(source.startsWith("http://") || source.startsWith("https://"))) return true
        return false
    }

    private fun looksLikeDrm(source: String): Boolean =
        source.contains("drm", ignoreCase = true) && source.contains("license", ignoreCase = true)

    private fun classifyFailure(context: Context, e: Exception): DownloadEnqueueOutcome.Failed {
        val code = DownloadErrorClassifier.classify(e)
        val msg = e.message.orEmpty()
        val display = when (code) {
            DownloadErrorCode.EMPTY_RESPONSE ->
                context.getString(R.string.download_error_empty_response)
            DownloadErrorCode.NO_SERVERS ->
                context.getString(R.string.download_error_no_servers)
            DownloadErrorCode.NOT_FOUND ->
                context.getString(R.string.download_error_not_found)
            DownloadErrorCode.EXPIRED ->
                context.getString(R.string.download_error_expired)
            DownloadErrorCode.FILE_MISSING ->
                context.getString(R.string.download_error_file_missing)
            DownloadErrorCode.STORAGE_RELOCATED ->
                context.getString(R.string.downloads_redownload_needed)
            else -> msg.ifBlank { code.name }
        }
        return DownloadEnqueueOutcome.Failed(code, display)
    }

    fun serializeVideoType(videoType: Video.Type): String {
        return when (videoType) {
            is Video.Type.Movie -> JSONObject()
                .put("kind", "movie")
                .put("id", videoType.id)
                .put("title", videoType.title)
                .put("releaseDate", videoType.releaseDate)
                .put("poster", videoType.poster)
                .put("imdbId", videoType.imdbId)
                .toString()
            is Video.Type.Episode -> JSONObject()
                .put("kind", "episode")
                .put("id", videoType.id)
                .put("number", videoType.number)
                .put("title", videoType.title)
                .put("poster", videoType.poster)
                .put("overview", videoType.overview)
                .put("showId", videoType.tvShow.id)
                .put("showTitle", videoType.tvShow.title)
                .put("showPoster", videoType.tvShow.poster)
                .put("showBanner", videoType.tvShow.banner)
                .put("seasonNumber", videoType.season.number)
                .put("seasonTitle", videoType.season.title)
                .toString()
        }
    }

    fun deserializeVideoType(json: String): Video.Type? {
        return runCatching {
            val o = JSONObject(json)
            when (o.optString("kind")) {
                "movie" -> Video.Type.Movie(
                    id = o.getString("id"),
                    title = o.optString("title"),
                    releaseDate = o.optString("releaseDate"),
                    poster = o.optString("poster"),
                    imdbId = o.optString("imdbId").takeIf { it.isNotBlank() },
                )
                "episode" -> Video.Type.Episode(
                    id = o.getString("id"),
                    number = o.optInt("number"),
                    title = o.optString("title").takeIf { it.isNotBlank() },
                    poster = o.optString("poster").takeIf { it.isNotBlank() },
                    overview = o.optString("overview").takeIf { it.isNotBlank() },
                    tvShow = Video.Type.Episode.TvShow(
                        id = o.optString("showId"),
                        title = o.optString("showTitle"),
                        poster = o.optString("showPoster").takeIf { it.isNotBlank() },
                        banner = o.optString("showBanner").takeIf { it.isNotBlank() },
                        releaseDate = null,
                        imdbId = null,
                    ),
                    season = Video.Type.Episode.Season(
                        number = o.optInt("seasonNumber", 1),
                        title = o.optString("seasonTitle").takeIf { it.isNotBlank() },
                    ),
                )
                else -> null
            }
        }.getOrNull()
    }
}
