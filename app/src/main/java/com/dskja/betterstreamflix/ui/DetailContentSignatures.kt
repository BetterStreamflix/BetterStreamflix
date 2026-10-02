package com.dskja.betterstreamflix.ui

import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow

/**
 * Detail list signatures used to skip full [submitList] rebuilds when only
 * My List / favorite (and similar header-only) state changed.
 *
 * Intentionally omits [Movie.isFavorite] / [TvShow.isFavorite] / [TvShow.isWatching]
 * so toggles that already update CTAs in place do not thrash hero/cover/child rows.
 */
object DetailContentSignatures {

    fun movie(movie: Movie): List<Any?> = listOf(
        movie.id,
        movie.title,
        movie.overview,
        movie.poster,
        movie.banner,
        // logo intentionally omitted — async LogoPersist must not reset detail tabs
        movie.trailer,
        movie.quality,
        movie.rating,
        movie.runtime,
        movie.isWatched,
        movie.watchHistory,
        movie.genres.size,
        movie.directors.size,
        movie.cast.size,
        movie.recommendations.size,
    )

    fun tvShow(tvShow: TvShow): List<Any?> = listOf(
        tvShow.id,
        tvShow.title,
        tvShow.overview,
        tvShow.poster,
        tvShow.banner,
        // logo intentionally omitted — async LogoPersist must not reset detail tabs
        tvShow.trailer,
        tvShow.quality,
        tvShow.rating,
        tvShow.runtime,
        tvShow.lastPlayedEpisodeId,
        tvShow.seasons.size,
        tvShow.seasons.sumOf { it.episodes.size },
        tvShow.seasons.map { "${it.id}:${it.episodes.size}" },
        tvShow.genres.size,
        tvShow.directors.size,
        tvShow.cast.size,
        tvShow.recommendations.size,
    )
}
