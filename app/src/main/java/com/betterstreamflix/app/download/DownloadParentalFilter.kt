package com.betterstreamflix.app.download

import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.models.Video
import com.betterstreamflix.app.utils.ParentalControlUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Age-gates download list rows when kids / parental filtering is active (PS-002). */
object DownloadParentalFilter {
    suspend fun filter(items: List<DownloadItemEntity>): List<DownloadItemEntity> {
        if (!ParentalControlUtils.isFilteringActive()) return items
        return coroutineScope {
            items.map { item ->
                async { item.takeIf { isAllowed(it) } }
            }.awaitAll().filterNotNull()
        }
    }

    suspend fun isAllowed(item: DownloadItemEntity): Boolean {
        if (!ParentalControlUtils.isFilteringActive()) return true
        val videoType = DownloadController.deserializeVideoType(item.videoTypeJson)
        return when (videoType) {
            is Video.Type.Movie -> ParentalControlUtils.isAllowed(
                Movie(
                    id = videoType.id,
                    title = videoType.title,
                    released = videoType.releaseDate.takeIf { it.isNotBlank() },
                    poster = videoType.poster,
                    imdbId = videoType.imdbId,
                    providerName = item.providerName,
                ),
            )
            is Video.Type.Episode -> ParentalControlUtils.isAllowed(
                TvShow(
                    id = videoType.tvShow.id,
                    title = videoType.tvShow.title,
                    poster = videoType.tvShow.poster,
                    banner = videoType.tvShow.banner,
                    providerName = item.providerName,
                ),
            )
            // No metadata to resolve a rating — fail-closed whenever filtering is active.
            null -> false
        }
    }
}
