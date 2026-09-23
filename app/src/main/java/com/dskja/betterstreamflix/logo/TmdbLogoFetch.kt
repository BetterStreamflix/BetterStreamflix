package com.dskja.betterstreamflix.logo

/**
 * Fetch seam for title logos — production talks to TMDb3; tests inject fakes
 * without initializing Retrofit / UserPreferences.
 */
object TmdbLogoFetch {

    data class SearchHit(
        val id: Int,
        val title: String?,
        val originalTitle: String?,
        val voteCount: Int,
        val popularity: Float,
        val year: Int?,
    )

    @Volatile
    var fetchMovieLogos: suspend (tmdbId: Int, includeImageLanguage: String?) -> List<TmdbLogoPicker.LogoCandidate>? =
        { tmdbId, includeImageLanguage -> defaultFetchMovie(tmdbId, includeImageLanguage) }

    @Volatile
    var fetchTvLogos: suspend (tmdbId: Int, includeImageLanguage: String?) -> List<TmdbLogoPicker.LogoCandidate>? =
        { tmdbId, includeImageLanguage -> defaultFetchTv(tmdbId, includeImageLanguage) }

    fun pickUrl(
        logos: List<TmdbLogoPicker.LogoCandidate>?,
        language: String?,
    ): String? {
        val path = TmdbLogoPicker.pickBestFilePath(
            logos = logos,
            language = language,
            isExcluded = { filePath -> TmdbLogoCache.isFilePathBlacklisted(filePath) },
        ) ?: return null
        return filePathToOriginalUrl(path)
    }

    /**
     * True miss (empty logos[]) may be remembered; a non-empty list that yielded no
     * usable URL (e.g. all blacklisted after decode fail) must not write a 6h sentinel.
     */
    fun shouldRememberMiss(logos: List<TmdbLogoPicker.LogoCandidate>?): Boolean =
        logos.isNullOrEmpty()

    /** Build TMDb original URL without touching TMDb3 (unit-test safe). */
    fun filePathToOriginalUrl(filePath: String): String {
        val trimmed = filePath.trim()
        val withSlash = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return "https://image.tmdb.org/t/p/original$withSlash"
    }

    fun resetToDefaults() {
        fetchMovieLogos = { tmdbId, includeImageLanguage ->
            defaultFetchMovie(tmdbId, includeImageLanguage)
        }
        fetchTvLogos = { tmdbId, includeImageLanguage ->
            defaultFetchTv(tmdbId, includeImageLanguage)
        }
    }

    private suspend fun defaultFetchMovie(
        tmdbId: Int,
        includeImageLanguage: String?,
    ): List<TmdbLogoPicker.LogoCandidate>? {
        val details = com.dskja.betterstreamflix.utils.TMDb3.Movies.details(
            movieId = tmdbId,
            appendToResponse = listOf(
                com.dskja.betterstreamflix.utils.TMDb3.Params.AppendToResponse.Movie.IMAGES,
            ),
            includeImageLanguage = includeImageLanguage,
        )
        return details.images?.logos?.map {
            TmdbLogoPicker.LogoCandidate(
                filePath = it.filePath,
                iso639 = it.iso639,
                voteCount = it.voteCount,
                voteAverage = it.voteAverage,
                width = it.width,
                height = it.height,
            )
        }
    }

    private suspend fun defaultFetchTv(
        tmdbId: Int,
        includeImageLanguage: String?,
    ): List<TmdbLogoPicker.LogoCandidate>? {
        val details = com.dskja.betterstreamflix.utils.TMDb3.TvSeries.details(
            seriesId = tmdbId,
            appendToResponse = listOf(
                com.dskja.betterstreamflix.utils.TMDb3.Params.AppendToResponse.Tv.IMAGES,
            ),
            includeImageLanguage = includeImageLanguage,
        )
        return details.images?.logos?.map {
            TmdbLogoPicker.LogoCandidate(
                filePath = it.filePath,
                iso639 = it.iso639,
                voteCount = it.voteCount,
                voteAverage = it.voteAverage,
                width = it.width,
                height = it.height,
            )
        }
    }
}
