package com.dskja.betterstreamflix.download

import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.utils.UserPreferences

/**
 * In-memory “currently watching” focus for [DownloadLanePolicy].
 * Updated from player start; cleared on hard player teardown.
 */
object DownloadLaneFocus {

    @Volatile
    private var focus: DownloadLanePolicy.WatchingFocus? = null

    fun current(): DownloadLanePolicy.WatchingFocus? = focus

    fun set(focus: DownloadLanePolicy.WatchingFocus?) {
        this.focus = focus
    }

    fun clear() {
        focus = null
    }

    fun setFromPlayback(videoType: Video.Type, providerName: String? = null) {
        val provider = providerName?.takeIf { it.isNotBlank() }
            ?: UserPreferences.currentProvider?.name
            ?: return
        focus = when (videoType) {
            is Video.Type.Movie -> DownloadLanePolicy.watchingFocusFrom(
                providerName = provider,
                contentKey = DownloadContentKey.movie(provider, videoType.id),
                seriesKey = null,
            )
            is Video.Type.Episode -> DownloadLanePolicy.watchingFocusFrom(
                providerName = provider,
                contentKey = DownloadContentKey.episode(
                    providerName = provider,
                    tvShowId = videoType.tvShow.id,
                    seasonNumber = videoType.season.number,
                    episodeNumber = videoType.number,
                    episodeId = videoType.id,
                ),
                seriesKey = SmartDownloadsPolicy.seriesKeyForShow(provider, videoType.tvShow.id),
            )
        }
    }
}
