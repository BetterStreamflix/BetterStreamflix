package com.dskja.betterstreamflix.download

import android.content.Context
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.UserPreferences

/** Button copy for detail-page download actions, including offline coverage. */
object DetailDownloadLabels {

    data class SeriesCoverage(
        val downloaded: Int,
        val total: Int,
        val seasonsWithPack: Int,
    ) {
        val hasAny: Boolean get() = downloaded > 0 || seasonsWithPack > 0
    }

    fun movieButton(context: Context, movie: Movie): String {
        val provider = movie.providerName ?: UserPreferences.currentProvider?.name
        val key = provider?.let { DownloadContentKey.movie(it, movie.id) }
        return if (key != null && OfflineBadgeStore.isCompleted(context, key)) {
            context.getString(R.string.detail_download_offline)
        } else {
            context.getString(R.string.option_show_download)
        }
    }

    fun seriesButton(
        context: Context,
        tvShow: TvShow,
        episodeToWatch: Episode?,
        seasonForDownload: Season?,
    ): String {
        val coverage = coverage(context, tvShow)
        if (coverage.total > 0) {
            val total = coverage.total
            return if (coverage.hasAny) {
                context.getString(
                    R.string.detail_download_coverage,
                    coverage.downloaded.coerceAtMost(total),
                    total,
                )
            } else {
                context.getString(R.string.detail_download_series, total)
            }
        }
        return when {
            episodeToWatch != null -> {
                val seasonNo = episodeToWatch.season?.number
                    ?: tvShow.seasons.firstOrNull { season ->
                        season.episodes.any { it.id == episodeToWatch.id }
                    }?.number
                    ?: 1
                context.getString(
                    R.string.detail_download_episode_short,
                    seasonNo,
                    episodeToWatch.number,
                )
            }
            seasonForDownload != null -> context.getString(
                R.string.detail_download_season_count,
                seasonForDownload.number,
                seasonForDownload.episodes.size,
            )
            else -> context.getString(R.string.option_show_download)
        }
    }

    fun coverage(context: Context, tvShow: TvShow): SeriesCoverage {
        val provider = tvShow.providerName ?: UserPreferences.currentProvider?.name
            ?: return SeriesCoverage(0, 0, 0)
        val keys = OfflineBadgeStore.completedKeys(context).value
        var downloaded = 0
        var total = 0
        var packs = 0
        tvShow.seasons.forEach { season ->
            val packKey = DownloadContentKey.seasonPack(provider, tvShow.id, season.number)
            // Prefer DownloadSeasonPackEntity COMPLETED (merged into OfflineBadgeStore keys);
            // fall back to legacy key membership for episode-only coverage.
            val packDone = OfflineBadgeStore.isCompleted(context, packKey) || keys.contains(packKey)
            if (packDone) packs += 1
            season.episodes.forEach { episode ->
                total += 1
                val episodeKey = DownloadContentKey.episode(
                    provider,
                    tvShow.id,
                    season.number,
                    episode.number,
                    episode.id,
                )
                if (packDone || keys.contains(episodeKey)) {
                    downloaded += 1
                }
            }
        }
        return SeriesCoverage(downloaded = downloaded, total = total, seasonsWithPack = packs)
    }
}
