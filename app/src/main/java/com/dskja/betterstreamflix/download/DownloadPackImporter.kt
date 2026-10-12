package com.dskja.betterstreamflix.download

import android.content.Context
import android.util.Log
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.utils.ParentalControlUtils
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DownloadPackImportResult(
    val queued: Int,
    val alreadyPresent: Int,
    val failed: Int,
    val packId: String?,
)

data class DownloadPackBuildResult(
    val payload: DownloadPackPayload,
    val truncated: Boolean,
)

object DownloadPackImporter {
    private const val TAG = "DownloadPackImporter"

    /**
     * Build a shareable pack from an existing season pack + its Room items.
     * Uses [DownloadItemEntity.videoTypeJson] when present; otherwise content-key fallback.
     */
    suspend fun buildFromSeasonPack(
        context: Context,
        pack: DownloadSeasonPackEntity,
    ): DownloadPackBuildResult = withContext(Dispatchers.IO) {
        val items = DownloadRepository.get(context).getAllOnce()
            .filter { DownloadProfileScope.matchesActive(it.profileId) }
            .filter { it.seasonPackId == pack.id }
            .sortedBy { it.sortIndex }
        require(items.isNotEmpty()) { "pack has no episodes" }
        val allRefs = items.mapNotNull { toEpisodeRef(it) }
        val truncated = allRefs.size > DownloadPackPayload.MAX_EPISODES
        val refs = allRefs.take(DownloadPackPayload.MAX_EPISODES)
        require(refs.isNotEmpty()) { "pack has no shareable episode metadata" }
        DownloadPackBuildResult(
            payload = DownloadPackPayload(
                providerName = pack.providerName.ifBlank { items.first().providerName },
                tvShowId = pack.tvShowId,
                tvShowTitle = pack.tvShowTitle.ifBlank { items.first().title },
                seasonNumber = pack.seasonNumber,
                posterUrl = pack.posterUrl.ifBlank { items.first().posterUrl },
                episodes = refs,
            ),
            truncated = truncated,
        )
    }

    /** Build a pack from an arbitrary episode list (same show/season preferred). */
    suspend fun buildFromItems(
        context: Context,
        items: List<DownloadItemEntity>,
        titleFallback: String = "",
    ): DownloadPackPayload = withContext(Dispatchers.IO) {
        require(items.isNotEmpty()) { "no episodes" }
        val episodeItems = items
            .filter { DownloadProfileScope.matchesActive(it.profileId) }
            .filter { it.kind == DownloadKind.EPISODE.name }
        require(episodeItems.isNotEmpty()) { "no episodes" }
        val allRefs = episodeItems.mapNotNull { toEpisodeRef(it) }
        val refs = allRefs.take(DownloadPackPayload.MAX_EPISODES)
        require(refs.isNotEmpty()) { "no shareable episode metadata" }
        val first = episodeItems.first()
        val videoType = DownloadController.deserializeVideoType(first.videoTypeJson) as? Video.Type.Episode
        val showId = videoType?.tvShow?.id
            ?: parseShowId(first.contentKey)
            ?: first.contentKey
        val season = videoType?.season?.number
            ?: parseSeason(first.contentKey)
            ?: 1
        DownloadPackPayload(
            providerName = first.providerName,
            tvShowId = showId,
            tvShowTitle = videoType?.tvShow?.title?.takeIf { it.isNotBlank() }
                ?: first.title.ifBlank { titleFallback },
            seasonNumber = season,
            posterUrl = videoType?.tvShow?.poster
                ?: first.posterUrl,
            episodes = refs,
        )
    }

    /**
     * Re-resolve each episode on the receiver and enqueue into the download queue,
     * grouping under a [DownloadSeasonPackEntity] when possible.
     */
    suspend fun importPayload(
        context: Context,
        payload: DownloadPackPayload,
        allowMetered: Boolean = false,
    ): DownloadPackImportResult = withContext(Dispatchers.IO) {
        val provider = Provider.findByName(payload.providerName)
            ?: Provider.providers.keys.find { it.name.equals(payload.providerName, ignoreCase = true) }
        if (provider == null) {
            throw IllegalStateException(
                context.getString(R.string.download_pack_import_unknown_provider, payload.providerName),
            )
        }
        val tvShow = TvShow(
            id = payload.tvShowId,
            title = payload.tvShowTitle,
            poster = payload.posterUrl.takeIf { it.isNotBlank() },
            providerName = provider.name,
        )
        if (ParentalControlUtils.isFilteringActive() && !ParentalControlUtils.isAllowed(tvShow)) {
            throw IllegalStateException(
                context.getString(R.string.download_pack_import_parental_blocked),
            )
        }
        if (ProfileManager.isKidsActive() && !ParentalControlUtils.isAllowed(tvShow)) {
            throw IllegalStateException(
                context.getString(R.string.download_pack_import_parental_blocked),
            )
        }

        val previousProvider = UserPreferences.currentProvider
        try {
            if (previousProvider?.name != provider.name) {
                UserPreferences.setCurrentProviderForPlayback(provider)
            }
            val packId = DownloadContentKey.seasonPack(
                provider.name,
                payload.tvShowId,
                payload.seasonNumber,
            )
            val repo = DownloadRepository.get(context)
            repo.upsertSeasonPack(
                DownloadSeasonPackEntity(
                    id = packId,
                    providerName = provider.name,
                    tvShowId = payload.tvShowId,
                    tvShowTitle = payload.tvShowTitle,
                    seasonNumber = payload.seasonNumber,
                    posterUrl = payload.posterUrl,
                    totalEpisodes = payload.episodes.size,
                    profileId = DownloadProfileScope.activeId(),
                ),
            )

            var queued = 0
            var already = 0
            var failed = 0
            payload.episodes.forEachIndexed { index, ref ->
                val episode = Episode(
                    id = ref.id,
                    number = ref.number,
                    title = ref.title.takeIf { it.isNotBlank() },
                    poster = ref.posterUrl.takeIf { it.isNotBlank() }
                        ?: payload.posterUrl.takeIf { it.isNotBlank() },
                    tvShow = tvShow,
                    season = Season(
                        id = "",
                        number = payload.seasonNumber,
                        title = null,
                    ),
                )
                if (ParentalControlUtils.isFilteringActive() &&
                    !ParentalControlUtils.isAllowed(tvShow)
                ) {
                    failed++
                    return@forEachIndexed
                }
                val outcome = enqueueEpisode(context, episode, allowMetered)
                when (outcome) {
                    is DownloadEnqueueOutcome.Started -> {
                        repo.assignPack(outcome.item.id, packId, index)
                        queued++
                    }
                    is DownloadEnqueueOutcome.AlreadyActive,
                    is DownloadEnqueueOutcome.AlreadyCompleted,
                    -> {
                        val item = when (outcome) {
                            is DownloadEnqueueOutcome.AlreadyActive -> outcome.item
                            is DownloadEnqueueOutcome.AlreadyCompleted -> outcome.item
                            else -> null
                        }
                        item?.let { repo.assignPack(it.id, packId, index) }
                        already++
                    }
                    is DownloadEnqueueOutcome.Failed -> {
                        Log.w(TAG, "Pack episode ${ref.id} failed: ${outcome.message}")
                        failed++
                    }
                    is DownloadEnqueueOutcome.NeedsOptions -> {
                        failed++
                    }
                    is DownloadEnqueueOutcome.SeasonStarted -> {
                        repo.assignPack(outcome.item.id, packId, index)
                        queued += outcome.started.coerceAtLeast(1)
                    }
                }
            }
            repo.refreshSeasonPack(packId)
            DownloadPackImportResult(
                queued = queued,
                alreadyPresent = already,
                failed = failed,
                packId = packId,
            )
        } finally {
            if (previousProvider != null &&
                previousProvider.name != UserPreferences.currentProvider?.name
            ) {
                UserPreferences.setCurrentProviderForPlayback(previousProvider)
            }
        }
    }

    private suspend fun enqueueEpisode(
        context: Context,
        episode: Episode,
        allowMetered: Boolean,
    ): DownloadEnqueueOutcome {
        val prepared = DownloadController.prepareEpisode(context, episode)
        return when (prepared) {
            is DownloadEnqueueOutcome.NeedsOptions -> {
                val trackIdx = when (UserPreferences.downloadQualityPreset) {
                    DownloadQualityPreset.DATA_SAVER ->
                        prepared.prepared.trackOptions.lastIndex.coerceAtLeast(0)
                    else -> 0
                }.coerceIn(0, prepared.prepared.trackOptions.lastIndex.coerceAtLeast(0))
                DownloadController.confirmEnqueue(
                    context = context,
                    prepared = prepared.prepared,
                    serverIndex = prepared.prepared.selectedServerIndex,
                    trackIndex = trackIdx,
                    qualityLabel = prepared.prepared.trackOptions.getOrNull(trackIdx)?.label
                        ?: context.getString(R.string.download_quality_auto),
                    allowMetered = allowMetered,
                )
            }
            else -> prepared
        }
    }

    private fun toEpisodeRef(entity: DownloadItemEntity): DownloadPackPayload.EpisodeRef? {
        val videoType = DownloadController.deserializeVideoType(entity.videoTypeJson) as? Video.Type.Episode
        if (videoType != null) {
            return DownloadPackPayload.EpisodeRef(
                id = videoType.id,
                number = videoType.number,
                title = videoType.title.orEmpty(),
                posterUrl = videoType.poster.orEmpty(),
            )
        }
        val parsed = parseEpisodeContentKey(entity.contentKey) ?: return null
        return DownloadPackPayload.EpisodeRef(
            id = parsed.episodeId,
            number = parsed.episodeNumber,
            title = entity.subtitle.substringAfter(" · ", missingDelimiterValue = "").trim(),
            posterUrl = entity.posterUrl,
        )
    }

    private data class ParsedKey(
        val episodeId: String,
        val episodeNumber: Int,
        val seasonNumber: Int,
        val tvShowId: String,
    )

    /** `episode|provider|tvShowId|season|epNum|episodeId` */
    private fun parseEpisodeContentKey(contentKey: String): ParsedKey? {
        val parts = contentKey.split('|')
        if (parts.size < 6 || parts[0] != "episode") return null
        val season = parts[3].toIntOrNull() ?: return null
        val epNum = parts[4].toIntOrNull() ?: return null
        val episodeId = parts[5].trim().takeIf { it.isNotEmpty() } ?: return null
        val showId = parts[2].trim().takeIf { it.isNotEmpty() } ?: return null
        return ParsedKey(episodeId, epNum, season, showId)
    }

    private fun parseShowId(contentKey: String): String? =
        parseEpisodeContentKey(contentKey)?.tvShowId

    private fun parseSeason(contentKey: String): Int? =
        parseEpisodeContentKey(contentKey)?.seasonNumber
}
