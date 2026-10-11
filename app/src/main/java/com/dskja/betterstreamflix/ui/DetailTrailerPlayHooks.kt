package com.dskja.betterstreamflix.ui

import android.os.Bundle
import android.view.View
import androidx.navigation.findNavController
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.fragments.movie.MovieMobileFragmentDirections
import com.dskja.betterstreamflix.fragments.movie.MovieTvFragmentDirections
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.utils.format

/**
 * Shared Trailer → Play session builders for Movie / TV detail (Mobile + Leanback).
 * Keeps warm target + “Jetzt streamen” navigation aligned with Watch Now, and
 * arms [TrailerStreamCrossfade] so the player never opens on a black frame.
 */
object DetailTrailerPlayHooks {

    fun movieSession(
        movie: Movie,
        host: View,
        onBeforePlay: (() -> Unit)? = null,
        leanback: Boolean,
    ): TrailerPlayPipeline.Session {
        val videoType = Video.Type.Movie(
            id = movie.id,
            title = movie.title,
            releaseDate = movie.released?.format("yyyy-MM-dd") ?: "",
            poster = movie.poster ?: movie.banner ?: "",
            imdbId = movie.imdbId,
        )
        return TrailerPlayPipeline.Session(
            target = TrailerPlayPipeline.Target(contentId = movie.id, videoType = videoType),
            onStreamNow = {
                onBeforePlay?.invoke()
                TrailerStreamCrossfade.arm(movie.id, videoType)
                TrailerStreamCrossfade.runWhenHot(host, movie.id) {
                    val nav = runCatching { host.findNavController() }.getOrNull()
                        ?: return@runWhenHot
                    if (leanback) {
                        nav.navigate(
                            MovieTvFragmentDirections.actionMovieToPlayer(
                                id = movie.id,
                                title = movie.title,
                                subtitle = movie.released?.format("yyyy") ?: "",
                                videoType = videoType,
                                preferredServerName = null,
                            ),
                        )
                    } else {
                        nav.navigate(
                            MovieMobileFragmentDirections.actionMovieToPlayer(
                                id = movie.id,
                                title = movie.title,
                                subtitle = movie.released?.format("yyyy") ?: "",
                                videoType = videoType,
                                preferredServerName = null,
                            ),
                        )
                    }
                }
            },
        )
    }

    fun tvShowSession(
        tvShow: TvShow,
        host: View,
        onBeforePlay: (() -> Unit)? = null,
        onNoEpisode: (() -> Unit)? = null,
        seasonNumber: Int?,
        isIptv: Boolean,
    ): TrailerPlayPipeline.Session {
        val episode = tvShow.episodeToWatch
        val target = if (episode != null && !isIptv) {
            TrailerPlayPipeline.Target(
                contentId = episode.id,
                videoType = episodeVideoType(tvShow, episode, seasonNumber),
            )
        } else {
            null
        }
        return TrailerPlayPipeline.Session(
            target = target,
            onStreamNow = {
                onBeforePlay?.invoke()
                if (isIptv || tvShow.episodeToWatch == null) {
                    onNoEpisode?.invoke()
                    return@Session
                }
                val episodeToPlay = tvShow.episodeToWatch ?: return@Session
                val videoType = episodeVideoType(tvShow, episodeToPlay, seasonNumber)
                TrailerStreamCrossfade.arm(episodeToPlay.id, videoType)
                TrailerStreamCrossfade.runWhenHot(host, episodeToPlay.id) {
                    val nav = runCatching { host.findNavController() }.getOrNull()
                        ?: return@runWhenHot
                    val args = Bundle().apply {
                        putString("id", episodeToPlay.id)
                        putString("title", tvShow.title)
                        putString(
                            "subtitle",
                            "S${videoType.season.number} E${videoType.number}  •  ${videoType.title}",
                        )
                        putSerializable("videoType", videoType)
                    }
                    nav.navigate(R.id.player, args)
                }
            },
        )
    }

    private fun episodeVideoType(
        tvShow: TvShow,
        episode: Episode,
        seasonNumber: Int?,
    ): Video.Type.Episode = Video.Type.Episode(
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
            imdbId = tvShow.imdbId,
        ),
        season = Video.Type.Episode.Season(
            number = seasonNumber ?: 1,
            title = "",
        ),
    )
}
