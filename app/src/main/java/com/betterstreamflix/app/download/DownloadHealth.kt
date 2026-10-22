package com.betterstreamflix.app.download

import android.content.Context
import com.betterstreamflix.app.R

/**
 * Offline download health: unplayable rows whose cache/file is missing or relocated.
 */
object DownloadHealth {

    data class Summary(
        val count: Int,
        val episodeCount: Int,
        val movieCount: Int,
        val ids: List<String>,
    ) {
        val hasUnhealthy: Boolean get() = count > 0

        /** True when every unhealthy row is an episode (product copy: “N Folgen…”). */
        val episodesOnly: Boolean get() = count > 0 && movieCount == 0
    }

    fun isUnhealthy(item: DownloadItemEntity): Boolean =
        DownloadSeriesFolders.needsRedownload(item)

    fun isUnhealthy(context: Context, item: DownloadItemEntity): Boolean =
        DownloadSeriesFolders.needsRedownload(context, item)

    fun summarize(items: List<DownloadItemEntity>): Summary {
        val unhealthy = items
            .filter(::isUnhealthy)
            .groupBy { it.contentKey }
            .map { (_, rows) -> rows.maxBy { it.updatedAt } }
        return Summary(
            count = unhealthy.size,
            episodeCount = unhealthy.count { it.kind == DownloadKind.EPISODE.name },
            movieCount = unhealthy.count { it.kind == DownloadKind.MOVIE.name },
            ids = unhealthy.map { it.id },
        )
    }

    /** Prefer live playability probe when a [Context] is available (OS-059). */
    fun summarize(context: Context, items: List<DownloadItemEntity>): Summary {
        val unhealthy = items
            .filter { isUnhealthy(context, it) }
            .groupBy { it.contentKey }
            .map { (_, rows) -> rows.maxBy { it.updatedAt } }
        return Summary(
            count = unhealthy.size,
            episodeCount = unhealthy.count { it.kind == DownloadKind.EPISODE.name },
            movieCount = unhealthy.count { it.kind == DownloadKind.MOVIE.name },
            ids = unhealthy.map { it.id },
        )
    }

    fun bannerMessage(context: Context, summary: Summary): String {
        if (!summary.hasUnhealthy) return ""
        return when {
            summary.count == 1 && summary.episodesOnly ->
                context.getString(R.string.downloads_health_unplayable_one)
            summary.episodesOnly ->
                context.getString(R.string.downloads_health_unplayable, summary.count)
            else ->
                context.getString(R.string.downloads_health_unplayable_mixed, summary.count)
        }
    }
}
