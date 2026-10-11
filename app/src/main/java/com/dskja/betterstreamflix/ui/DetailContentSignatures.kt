package com.dskja.betterstreamflix.ui

import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow

/**
 * Detail list signatures used to skip full [submitList] rebuilds when only
 * My List / favorite (and similar header-only) state changed.
 *
 * Intentionally omits [Movie.isFavorite] / [TvShow.isFavorite] / [TvShow.isWatching],
 * [Movie.isWatched] / [Movie.watchHistory], and per-season episode-load counts
 * so header CTA / async episode fills do not thrash hero/cover or reset tabs.
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
        // isWatched / watchHistory omitted — header CTA updates in place
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
        // lastPlayedEpisodeId omitted — RW/CW updates must not rebuild detail body
        tvShow.seasons.size,
        // Episode-fill churn omitted — season rows refresh in place; keep selectedTab stable
        tvShow.seasons.map { it.id },
        tvShow.genres.size,
        tvShow.directors.size,
        tvShow.cast.size,
        tvShow.recommendations.size,
    )
}
