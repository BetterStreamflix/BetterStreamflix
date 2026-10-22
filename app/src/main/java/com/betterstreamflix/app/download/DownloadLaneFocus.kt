package com.betterstreamflix.app.download

import com.betterstreamflix.app.models.Video
import com.betterstreamflix.app.utils.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory “currently watching” focus for [DownloadLanePolicy].
 * Updated from player start; cleared on hard player teardown.
 * [tick] invalidates Offline Studio / Queue collectors without a Room write.
 */
object DownloadLaneFocus {

    private val focusState = MutableStateFlow<DownloadLanePolicy.WatchingFocus?>(null)

    /** Emits on every set/clear so Studio lane counts refresh immediately. */
    val tick: StateFlow<DownloadLanePolicy.WatchingFocus?> = focusState.asStateFlow()

    fun current(): DownloadLanePolicy.WatchingFocus? = focusState.value

    fun set(focus: DownloadLanePolicy.WatchingFocus?) {
        focusState.value = focus
    }

    fun clear() {
        if (focusState.value == null) return
        focusState.value = null
    }

    fun setFromPlayback(videoType: Video.Type, providerName: String? = null) {
        val provider = providerName?.takeIf { it.isNotBlank() }
            ?: UserPreferences.currentProvider?.name
            ?: return
        focusState.value = when (videoType) {
            is Video.Type.Movie -> DownloadLanePolicy.watchingFocusFrom(
                providerName = provider,
                contentKey = DownloadContentKey.movie(provider, videoType.id),
                // Episode-only series boost; movies stay contentKey-scoped.
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
                // While playing, boost same-series downloads; cleared on player teardown.
                seriesKey = SmartDownloadsPolicy.seriesKeyForShow(provider, videoType.tvShow.id),
            )
        }
    }

    /**
     * Hard player teardown — drop Watching boost and let the lane scheduler replan.
     */
    fun clearFromPlayerTeardown(context: android.content.Context?) {
        clear()
        context?.applicationContext?.let { DownloadLaneScheduler.applyAsync(it) }
    }
}
