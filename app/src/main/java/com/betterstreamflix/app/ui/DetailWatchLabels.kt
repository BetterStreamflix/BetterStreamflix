package com.betterstreamflix.app.ui

import android.content.Context
import com.betterstreamflix.app.R
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.models.WatchItem
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
        if (iptv) {
            return context.getString(R.string.movie_watch_now)
        }
        if (showsEpisodePicker(iptv = false, episodeNumber)) {
            return context.getString(R.string.detail_watch_choose_episode)
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

    /** Watch cannot start until the user picks an episode. */
    fun showsEpisodePicker(iptv: Boolean, episodeNumber: Int?): Boolean =
        !iptv && episodeNumber == null

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

    /** Whole minutes still left (at least 1 when any time remains). */
    fun remainingMinutes(history: WatchItem.WatchHistory?): Int? {
        if (history == null || history.durationMillis <= 0L) return null
        val leftMs = (history.durationMillis - history.lastPlaybackPositionMillis).coerceAtLeast(0L)
        if (leftMs <= 0L) return null
        return ((leftMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
    }

    fun remainingMinutesLabel(context: Context, history: WatchItem.WatchHistory?): String? {
        val minutes = remainingMinutes(history) ?: return null
        return context.getString(R.string.continue_watching_minutes_left, minutes)
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
