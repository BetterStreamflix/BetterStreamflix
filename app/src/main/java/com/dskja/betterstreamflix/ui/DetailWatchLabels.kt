package com.dskja.betterstreamflix.ui

import android.content.Context
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.WatchItem
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Resume-aware Watch CTA copy for Movie / TV Show detail (Mobile + Leanback).
 * Keeps progress bars as the visual cue; labels encode resume state like Featured / iOS.
 */
object DetailWatchLabels {

    fun movie(context: Context, movie: Movie): String {
        val history = movie.watchHistory
        val percent = progressPercent(history)
        return if (percent in 1..95) {
            context.getString(R.string.detail_watch_resume, percent)
        } else {
            context.getString(R.string.movie_watch_now)
        }
    }

    fun tvShow(
        context: Context,
        tvShow: TvShow,
        seasonNumber: Int?,
        episodeNumber: Int?,
        iptv: Boolean,
    ): String {
        if (iptv || episodeNumber == null) {
            return context.getString(R.string.movie_watch_now)
        }
        val season = seasonNumber ?: 1
        val history = tvShow.episodeToWatch?.watchHistory
        val percent = progressPercent(history)
        val position = history?.takeIf { percent in 1..95 }?.let {
            formatPosition(it.lastPlaybackPositionMillis)
        }
        return when {
            position != null -> context.getString(
                R.string.detail_watch_resume_episode,
                season,
                episodeNumber,
                position,
            )
            else -> context.getString(
                R.string.tv_show_watch_season_episode,
                season,
                episodeNumber,
            )
        }
    }

    fun episodeMetaAccent(context: Context, history: WatchItem.WatchHistory?, watched: Boolean): String? {
        val percent = progressPercent(history)
        return when {
            percent in 1..95 && history != null ->
                context.getString(
                    R.string.detail_episode_resume,
                    formatPosition(history.lastPlaybackPositionMillis),
                )
            watched -> context.getString(R.string.detail_episode_watched)
            else -> null
        }
    }

    fun progressPercent(history: WatchItem.WatchHistory?): Int {
        if (history == null || history.durationMillis <= 0L) return 0
        return (history.lastPlaybackPositionMillis * 100 /
            history.durationMillis.toDouble()).toInt().coerceIn(0, 100)
    }

    fun formatPosition(millis: Long): String {
        val totalSec = TimeUnit.MILLISECONDS.toSeconds(millis.coerceAtLeast(0L))
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }
}
