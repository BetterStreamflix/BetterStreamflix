package com.dskja.betterstreamflix.utils

import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Genre
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.People
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.models.Show
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.logo.LogoConstants
import com.dskja.betterstreamflix.logo.TmdbLogoCache
import com.dskja.betterstreamflix.logo.TmdbLogoFetch
import com.dskja.betterstreamflix.logo.TmdbLogoPicker
import com.dskja.betterstreamflix.logo.TmdbLogoTelemetry
import com.dskja.betterstreamflix.utils.TMDb3.original
import com.dskja.betterstreamflix.utils.TMDb3.w500
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

object TmdbUtils {
    private const val MIN_ACCEPTABLE_SCORE = 60
    private const val MIN_ACCEPTABLE_SCORE_WITH_YEAR = 80
    private const val WEAK_CONTAINS_SCORE = 70
    private const val MAX_LOCALIZED_DETAIL_CANDIDATES = 5
    private const val UNKNOWN_AGE_RATING = Int.MIN_VALUE
    private val movieAgeCache = ConcurrentHashMap<String, Int>()
    private val tvAgeCache = ConcurrentHashMap<String, Int>()

    fun clearLogoCaches() {
        TmdbLogoCache.clearAll()
    }

    private fun logoCacheKey(tmdbId: Int, language: String?) =
        TmdbLogoPicker.cacheKey(tmdbId, language)

    /**
     * Picks the title logo that best matches the UI language, preferring a language hit,
     * then English, then language-less artwork, and finally the highest-voted / widest file.
     * Skips SVG.
     */
    private fun pickBestLogo(
        logos: List<TMDb3.Images.FileImage>?,
        language: String?,
    ): String? {
        if (!UserPreferences.enableTmdbLogos) return null
        val path = TmdbLogoPicker.pickBestFilePath(
            logos = logos?.map {
                TmdbLogoPicker.LogoCandidate(
                    filePath = it.filePath,
                    iso639 = it.iso639,
                    voteCount = it.voteCount,
                    voteAverage = it.voteAverage,
                    width = it.width,
                    height = it.height,
                )
            },
            language = language,
            isExcluded = { filePath -> TmdbLogoCache.isFilePathBlacklisted(filePath) },
        ) ?: return null
        return path.original
    }

    private fun includeLanguageList(language: String?): String {
        // Align with cache primaryLanguage: primary + en + null.
        val lang = TmdbLogoPicker.primaryLanguage(language).takeIf { it.isNotBlank() }
        return if (lang == null || lang == "en") "en,null" else "$lang,en,null"
    }

    /**
     * Fetches a title logo by TMDb id. [LOGO_MISS] is stored only after a successful
     * details response with no usable logo — network / parse errors must not poison the cache.
     */
    private suspend fun getMovieLogo(tmdbId: Int, language: String?): String? {
        if (!UserPreferences.enableTmdbLogos) return null
        val cacheKey = logoCacheKey(tmdbId, language)
        when (val cached = TmdbLogoCache.lookup(cacheKey)) {
            is TmdbLogoCache.Lookup.Hit -> return cached.url
            TmdbLogoCache.Lookup.KnownMiss -> return null
            TmdbLogoCache.Lookup.Fetch -> Unit
        }

        val flightKey = TmdbLogoPicker.inflightKey(isTv = false, tmdbId = tmdbId, language = language)
        TmdbLogoCache.getInflight(flightKey)?.let { return it.await() }

        val deferred = CompletableDeferred<String?>()
        val winner = TmdbLogoCache.putInflight(flightKey, deferred)
        if (winner !== deferred) return winner.await()

        try {
            val logos = withTimeoutOrNull(LogoConstants.RESOLVE_TIMEOUT_MS) {
                fetchLogosWithRetry {
                    TmdbLogoFetch.fetchMovieLogos(tmdbId, includeLanguageList(language))
                }
            } ?: run {
                deferred.complete(null)
                return null
            }
            val resolved = TmdbLogoFetch.pickUrl(logos, language)?.takeIf { it.isNotBlank() }
            if (resolved != null) {
                TmdbLogoCache.put(cacheKey, resolved)
                TmdbLogoTelemetry.recordNetworkSuccess()
            } else if (TmdbLogoFetch.shouldRememberMiss(logos)) {
                // True empty logos[] — remember miss. Do NOT miss-poison when candidates
                // existed but were all blacklisted (decode-fail alternate exhausted).
                TmdbLogoCache.put(cacheKey, null)
            }
            deferred.complete(resolved)
            return resolved
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            TmdbLogoTelemetry.recordNetworkFailure()
            deferred.complete(null)
            return null
        } finally {
            if (!deferred.isCompleted) deferred.complete(null)
            TmdbLogoCache.removeInflight(flightKey, deferred)
        }
    }

    private suspend fun getTvShowLogo(tmdbId: Int, language: String?): String? {
        if (!UserPreferences.enableTmdbLogos) return null
        val cacheKey = logoCacheKey(tmdbId, language)
        when (val cached = TmdbLogoCache.lookup(cacheKey)) {
            is TmdbLogoCache.Lookup.Hit -> return cached.url
            TmdbLogoCache.Lookup.KnownMiss -> return null
            TmdbLogoCache.Lookup.Fetch -> Unit
        }

        val flightKey = TmdbLogoPicker.inflightKey(isTv = true, tmdbId = tmdbId, language = language)
        TmdbLogoCache.getInflight(flightKey)?.let { return it.await() }

        val deferred = CompletableDeferred<String?>()
        val winner = TmdbLogoCache.putInflight(flightKey, deferred)
        if (winner !== deferred) return winner.await()

        try {
            val logos = withTimeoutOrNull(LogoConstants.RESOLVE_TIMEOUT_MS) {
                fetchLogosWithRetry {
                    TmdbLogoFetch.fetchTvLogos(tmdbId, includeLanguageList(language))
                }
            } ?: run {
                deferred.complete(null)
                return null
            }
            val resolved = TmdbLogoFetch.pickUrl(logos, language)?.takeIf { it.isNotBlank() }
            if (resolved != null) {
                TmdbLogoCache.put(cacheKey, resolved)
                TmdbLogoTelemetry.recordNetworkSuccess()
            } else if (TmdbLogoFetch.shouldRememberMiss(logos)) {
                TmdbLogoCache.put(cacheKey, null)
            }
            deferred.complete(resolved)
            return resolved
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            TmdbLogoTelemetry.recordNetworkFailure()
            deferred.complete(null)
            return null
        } finally {
            if (!deferred.isCompleted) deferred.complete(null)
            TmdbLogoCache.removeInflight(flightKey, deferred)
        }
    }

    private suspend fun <T> fetchLogosWithRetry(block: suspend () -> T): T {
        var last: Exception? = null
        repeat(LogoConstants.NETWORK_RETRY_COUNT + 1) { attempt ->
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                if (attempt < LogoConstants.NETWORK_RETRY_COUNT) {
                    delay(LogoConstants.NETWORK_RETRY_BASE_DELAY_MS * (attempt + 1))
                }
            }
        }
        throw last ?: IllegalStateException("logo fetch failed")
    }

    /**
     * Resolves a title logo for surfaces that only know the title (Featured card).
     * Uses the TMDb id when the caller already has one, otherwise matches by title/year,
     * then without year, then a direct search fallback. Optional [imdbId] is tried next.
     */
    suspend fun resolveTitleLogo(
        title: String,
        year: Int? = null,
        isTv: Boolean = false,
        tmdbId: String? = null,
        imdbId: String? = null,
        language: String? = null,
    ): String? {
        if (!UserPreferences.enableTmdb) return null
        if (!UserPreferences.enableTmdbLogos) return null
        return try {
            withTimeout(LogoConstants.RESOLVE_TIMEOUT_MS) {
                resolveTitleLogoUncapped(
                    title = title,
                    year = year,
                    isTv = isTv,
                    tmdbId = tmdbId,
                    imdbId = imdbId,
                    language = language,
                )
            }
        } catch (e: TimeoutCancellationException) {
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun resolveTitleLogoUncapped(
        title: String,
        year: Int? = null,
        isTv: Boolean = false,
        tmdbId: String? = null,
        imdbId: String? = null,
        language: String? = null,
    ): String? {
        val lang = language ?: UserPreferences.currentProvider?.language
        val effectiveYear = year ?: extractYear(title)
        val normalizedQuery = titleNormalizer(title)
        val id = tmdbId?.trim()?.toIntOrNull()
            ?: suspendCatching {
                val cleanImdb = imdbId?.trim()?.takeIf { it.isNotBlank() }
                when {
                    cleanImdb != null && isTv ->
                        getTvShowByImdbId(cleanImdb, lang)?.tmdbId?.toIntOrNull()
                    cleanImdb != null ->
                        getMovieByImdbId(cleanImdb, lang)?.tmdbId?.toIntOrNull()
                    else -> null
                }
            }
            ?: suspendCatching {
                if (isTv) {
                    findBestTvMatch(title, effectiveYear, lang)?.id
                        ?: effectiveYear?.let {
                            // Year-less remake guard: still prefer candidates near the year.
                            findBestTvMatch(title, year = null, lang)?.id
                        }
                        ?: searchTvDirect(
                            query = normalizedQuery.ifBlank { title },
                            language = lang,
                            preferredYear = effectiveYear,
                        )?.id
                } else {
                    findBestMovieMatch(title, effectiveYear, lang)?.id
                        ?: effectiveYear?.let {
                            findBestMovieMatch(title, year = null, lang)?.id
                        }
                        ?: searchMovieDirect(
                            query = normalizedQuery.ifBlank { title },
                            language = lang,
                            preferredYear = effectiveYear,
                        )?.id
                }
            }
            ?: return null
        return if (isTv) getTvShowLogo(id, lang) else getMovieLogo(id, lang)
    }

    /** Like [runCatching] but never swallows [CancellationException]. */
    private suspend inline fun <T> suspendCatching(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /** Direct [TMDb3.Search.movie] when scored matching returns nothing. */
    private suspend fun searchMovieDirect(
        query: String,
        language: String?,
        preferredYear: Int? = null,
    ): TMDb3.Movie? {
        if (query.isBlank()) return null
        return suspendCatching {
            TMDb3.Search.movie(query = query, language = language).results
                .filter {
                    TmdbLogoPicker.isAcceptableSearchHit(
                        voteCount = it.voteCount,
                        popularity = it.popularity,
                        queryTitle = query,
                        candidateTitles = listOf(it.title, it.originalTitle),
                        normalize = ::titleNormalizer,
                        releaseYear = preferredYear,
                        candidateYear = it.releaseDate?.take(4)?.toIntOrNull(),
                    )
                }
                .maxWithOrNull(
                    compareBy<TMDb3.Movie> { it.voteCount }
                        .thenBy { it.popularity }
                        .thenByDescending {
                            val y = it.releaseDate?.take(4)?.toIntOrNull()
                            if (preferredYear != null && y != null) {
                                -kotlin.math.abs(preferredYear - y)
                            } else {
                                0
                            }
                        },
                )
        }
    }

    /** Direct [TMDb3.Search.tv] when scored matching returns nothing. */
    private suspend fun searchTvDirect(
        query: String,
        language: String?,
        preferredYear: Int? = null,
    ): TMDb3.Tv? {
        if (query.isBlank()) return null
        return suspendCatching {
            TMDb3.Search.tv(query = query, language = language).results
                .filter {
                    TmdbLogoPicker.isAcceptableSearchHit(
                        voteCount = it.voteCount,
                        popularity = it.popularity,
                        queryTitle = query,
                        candidateTitles = listOf(it.name, it.originalName),
                        normalize = ::titleNormalizer,
                        releaseYear = preferredYear,
                        candidateYear = it.firstAirDate?.take(4)?.toIntOrNull(),
                    )
                }
                .maxWithOrNull(
                    compareBy<TMDb3.Tv> { it.voteCount }
                        .thenBy { it.popularity }
                        .thenByDescending {
                            val y = it.firstAirDate?.take(4)?.toIntOrNull()
                            if (preferredYear != null && y != null) {
                                -kotlin.math.abs(preferredYear - y)
                            } else {
                                0
                            }
                        },
                )
        }
    }

    /**
     * True when we have enough keys to attempt a TMDb trailer lookup.
     * Trailer CTA/section gates should use this instead of tmdbId alone.
     */
    fun hasTrailerLookupKeys(
        tmdbId: String? = null,
        imdbId: String? = null,
        title: String? = null,
        year: Int? = null,
    ): Boolean {
        if (!tmdbId.isNullOrBlank()) return true
        if (!imdbId.isNullOrBlank()) return true
        if (!title.isNullOrBlank() && year != null) return true
        return false
    }

    /**
     * Trailers/teasers for a title: (title, watch URL, type label).
     * Prefers official YouTube; falls back to Vimeo when no YouTube exists.
     * Resolves a TMDb id via [tmdbId], [imdbId], or title+year when needed.
     * When [seasonNumber] is set for TV, uses the season videos endpoint.
     */
    suspend fun listYoutubeTrailers(
        tmdbId: String? = null,
        isTv: Boolean,
        title: String? = null,
        year: Int? = null,
        imdbId: String? = null,
        seasonNumber: Int? = null,
    ): List<Triple<String, String, String>> {
        if (!UserPreferences.enableTmdb) return emptyList()
        val lang = UserPreferences.currentProvider?.language
        val id = tmdbId?.trim()?.toIntOrNull()
            ?: runCatching {
                val cleanImdb = imdbId?.trim()?.takeIf { it.isNotBlank() }
                when {
                    cleanImdb != null && isTv -> getTvShowByImdbId(cleanImdb, lang)?.tmdbId?.toIntOrNull()
                    cleanImdb != null -> getMovieByImdbId(cleanImdb, lang)?.tmdbId?.toIntOrNull()
                    !title.isNullOrBlank() && isTv -> findBestTvMatch(title, year ?: extractYear(title), lang)?.id
                    !title.isNullOrBlank() -> findBestMovieMatch(title, year ?: extractYear(title), lang)?.id
                    else -> null
                }
            }.getOrNull()
            ?: return emptyList()
        return runCatching {
            val videos = when {
                isTv && seasonNumber != null -> {
                    TMDb3.TvSeasons.details(
                        seriesId = id,
                        seasonNumber = seasonNumber,
                        appendToResponse = listOf(TMDb3.Params.AppendToResponse.TvSeason.VIDEOS),
                        language = lang,
                        includeVideoLanguage = includeLanguageList(lang),
                    ).videos?.results
                }
                isTv -> {
                    TMDb3.TvSeries.details(
                        seriesId = id,
                        appendToResponse = listOf(TMDb3.Params.AppendToResponse.Tv.VIDEOS),
                        includeVideoLanguage = includeLanguageList(lang),
                    ).videos?.results
                }
                else -> {
                    TMDb3.Movies.details(
                        movieId = id,
                        appendToResponse = listOf(TMDb3.Params.AppendToResponse.Movie.VIDEOS),
                        includeVideoLanguage = includeLanguageList(lang),
                    ).videos?.results
                }
            }.orEmpty()
            rankTrailerVideos(videos)
                .mapNotNull { video ->
                    val url = videoWatchUrl(video) ?: return@mapNotNull null
                    Triple(
                        video.name?.takeIf { it.isNotBlank() } ?: "Trailer",
                        url,
                        video.type?.value ?: "Trailer",
                    )
                }
                .distinctBy { it.second }
                .take(5)
        }.getOrDefault(emptyList())
    }

    private fun videoWatchUrl(video: TMDb3.Video): String? {
        val key = video.key?.takeIf { it.isNotBlank() } ?: return null
        return when (video.site) {
            TMDb3.Video.VideoSite.YOUTUBE -> "https://www.youtube.com/watch?v=$key"
            TMDb3.Video.VideoSite.VIMEO -> "https://vimeo.com/$key"
            else -> null
        }
    }

    /**
     * Prefer YouTube; if none, accept Vimeo. Within the pool: official first,
     * then trailer/teaser/clip, then newer [publishedAt].
     */
    private fun rankTrailerVideos(videos: List<TMDb3.Video>): List<TMDb3.Video> {
        val withKey = videos.filter { !it.key.isNullOrBlank() && it.site != null }
        val youtube = withKey.filter { it.site == TMDb3.Video.VideoSite.YOUTUBE }
        val pool = if (youtube.isNotEmpty()) {
            youtube
        } else {
            withKey.filter { it.site == TMDb3.Video.VideoSite.VIMEO }
        }
        return pool.sortedWith(
            compareBy<TMDb3.Video> { if (it.official == true) 0 else 1 }
                .thenBy {
                    when (it.type) {
                        TMDb3.Video.VideoType.TRAILER -> 0
                        TMDb3.Video.VideoType.TEASER -> 1
                        TMDb3.Video.VideoType.CLIP -> 2
                        else -> 3
                    }
                }
                .thenByDescending { it.publishedAt.orEmpty() },
        )
    }

    /**
     * Prefers official trailers, then teasers, then clips.
     * YouTube first; Vimeo only when no YouTube video exists. Newer publishedAt wins.
     */
    private fun pickBestYoutubeTrailerUrl(videos: List<TMDb3.Video>?): String? {
        return rankTrailerVideos(videos.orEmpty()).firstOrNull()?.let { videoWatchUrl(it) }
    }

    /**
     * Resolve a TMDb person (biography + combined credits). Filmography uses TMDb
     * ids so [ShowLookup] can map them onto the current provider when opened.
     * Includes both cast and crew so directors / writers get their credits too.
     */
    suspend fun getPeopleById(personId: Int, language: String? = null): People? {
        return runCatching {
            val detail = TMDb3.People.details(
                personId = personId,
                appendToResponse = listOf(TMDb3.Params.AppendToResponse.Person.COMBINED_CREDITS),
                language = language,
            )
            People(
                id = detail.id.toString(),
                name = detail.name,
                image = detail.profilePath?.original,
                biography = detail.biography?.takeIf { it.isNotBlank() },
                placeOfBirth = detail.placeOfBirth?.takeIf { it.isNotBlank() },
                birthday = detail.birthday,
                deathday = detail.deathday,
                filmography = filmographyFromCredits(detail.combinedCredits),
                knownForDepartment = detail.knownForDepartment?.value,
            )
        }.getOrNull()
    }

    private fun filmographyFromCredits(
        credits: TMDb3.Person.Credits<TMDb3.MultiItem>?,
    ): List<Show> {
        data class Ranked(val show: Show, val popularity: Float, val vote: Float)

        fun toRanked(multi: TMDb3.MultiItem): Ranked? {
            return when (multi) {
                is TMDb3.Movie -> {
                    if (multi.title.isBlank()) return null
                    Ranked(
                        show = Movie(
                            id = multi.id.toString(),
                            title = multi.title,
                            overview = multi.overview,
                            released = multi.releaseDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w500,
                            banner = multi.backdropPath?.original,
                            tmdbId = multi.id.toString(),
                        ),
                        popularity = multi.popularity,
                        vote = multi.voteAverage,
                    )
                }
                is TMDb3.Tv -> {
                    if (multi.name.isBlank()) return null
                    Ranked(
                        show = TvShow(
                            id = multi.id.toString(),
                            title = multi.name,
                            overview = multi.overview,
                            released = multi.firstAirDate,
                            rating = multi.voteAverage.toDouble(),
                            poster = multi.posterPath?.w500,
                            banner = multi.backdropPath?.original,
                            tmdbId = multi.id.toString(),
                        ),
                        popularity = multi.popularity ?: 0f,
                        vote = multi.voteAverage,
                    )
                }
                else -> null
            }
        }

        val seen = LinkedHashSet<String>()
        return (credits?.cast.orEmpty() + credits?.crew.orEmpty())
            .mapNotNull(::toRanked)
            .sortedWith(
                compareByDescending<Ranked> { it.popularity }
                    .thenByDescending { it.vote },
            )
            .mapNotNull { ranked ->
                val key = when (val show = ranked.show) {
                    is Movie -> "m:${show.tmdbId ?: show.id}"
                    is TvShow -> "t:${show.tmdbId ?: show.id}"
                    else -> return@mapNotNull null
                }
                if (!seen.add(key)) null else ranked.show
            }
    }

    suspend fun getMovie(title: String, year: Int? = null, language: String? = null): Movie? {
        if (!UserPreferences.enableTmdb) return null
        return try {
            val effectiveYear = year ?: extractYear(title)
            val cacheKey = buildLookupCacheKey("movie-match", title, effectiveYear, language)
            if (TmdbCache.hasSearchMovie(cacheKey)) {
                val cachedId = TmdbCache.getSearchMovieId(cacheKey) ?: return null
                return getMovieById(cachedId, language)
            }
            val movie = findBestMovieMatch(title, effectiveYear, language)
            TmdbCache.putSearchMovieId(cacheKey, movie?.id)
            movie?.let { getMovieById(it.id, language) }
        } catch (_: Exception) { null }
    }

    suspend fun getMovieById(tmdbId: Int, language: String? = null): Movie? {
        if (!UserPreferences.enableTmdb) return null
        val lang = language ?: UserPreferences.currentProvider?.language
        TmdbCache.getMovie(tmdbId, lang)?.let { cached ->
            return Movie(
                id = cached.id.toString(),
                title = cached.title,
                overview = cached.overview,
                released = cached.released,
                runtime = cached.runtime,
                trailer = cached.trailer,
                rating = cached.rating,
                poster = cached.poster,
                banner = cached.banner,
                imdbId = cached.imdbId,
                tmdbId = cached.id.toString(),
                genres = cached.genres.map { Genre(it.first, it.second) },
                cast = cached.cast.map { People(it.first, it.second, it.third) },
                directors = cached.directors.map { People(it.first, it.second, it.third) },
                recommendations = cached.recommendations.map { it.toShow() },
            ).also { movie ->
                movie.contentRating = cached.contentRating
                movie.logo = cached.logo
                    ?.takeIf { UserPreferences.enableTmdbLogos }
                    ?.takeUnless { url -> TmdbLogoCache.isBlacklisted(url) }
                movie.logoLanguage = when {
                    !UserPreferences.enableTmdbLogos || movie.logo.isNullOrBlank() -> null
                    !cached.logoLanguage.isNullOrBlank() -> cached.logoLanguage
                    else -> lang
                }
                movie.logoSource = if (UserPreferences.enableTmdbLogos) {
                    com.dskja.betterstreamflix.logo.TmdbLogoPicker.inferSource(movie.logo)
                } else {
                    com.dskja.betterstreamflix.logo.LogoSource.UNKNOWN
                }
            }
        }
        return try {
            val details = TMDb3.Movies.details(
                movieId = tmdbId,
                appendToResponse = listOf(
                    TMDb3.Params.AppendToResponse.Movie.CREDITS,
                    TMDb3.Params.AppendToResponse.Movie.RECOMMENDATIONS,
                    TMDb3.Params.AppendToResponse.Movie.VIDEOS,
                    TMDb3.Params.AppendToResponse.Movie.EXTERNAL_IDS,
                    TMDb3.Params.AppendToResponse.Movie.RELEASES_DATES,
                    TMDb3.Params.AppendToResponse.Movie.IMAGES,
                ),
                language = lang,
                includeImageLanguage = includeLanguageList(lang),
                includeVideoLanguage = includeLanguageList(lang),
            )
            val directors = details.credits?.crew
                ?.filter { it.job.equals("Director", ignoreCase = true) }
                ?.distinctBy { it.id }
                ?.map { People(it.id.toString(), it.name, it.profilePath?.w500) }
                .orEmpty()
            val recommendations = mapRecommendations(details.recommendations?.results)
            val contentRating = extractMovieCertification(details, lang)
            val previousLogo = TmdbCache.getMovie(tmdbId, lang)?.logo
            val logo = if (UserPreferences.enableTmdbLogos) {
                val picked = pickBestLogo(details.images?.logos, lang)
                // Only write logo cache when logos are enabled — otherwise a null put
                // would poison the key with a 6h KnownMiss after the user re-enables.
                // Miss-poison only for true empty logo lists (shouldRememberMiss).
                val candidates = details.images?.logos?.map {
                    TmdbLogoPicker.LogoCandidate(
                        filePath = it.filePath,
                        iso639 = it.iso639,
                        voteCount = it.voteCount,
                        voteAverage = it.voteAverage,
                        width = it.width,
                        height = it.height,
                    )
                }
                if (picked != null || TmdbLogoFetch.shouldRememberMiss(candidates)) {
                    TmdbLogoCache.put(logoCacheKey(tmdbId, lang), picked)
                }
                picked
            } else {
                // Keep any previously cached logo so disabling the feature does not wipe it.
                previousLogo
            }
            val stampedLang = lang.takeIf {
                UserPreferences.enableTmdbLogos && !logo.isNullOrBlank()
            }
            val result = Movie(
                id = details.id.toString(),
                title = details.title,
                overview = details.overview,
                released = details.releaseDate,
                runtime = details.runtime,
                trailer = pickBestYoutubeTrailerUrl(details.videos?.results),
                rating = details.voteAverage.toDouble(),
                poster = details.posterPath?.original,
                banner = details.backdropPath?.original,
                imdbId = details.externalIds?.imdbId,
                tmdbId = details.id.toString(),
                genres = details.genres.map { Genre(it.id.toString(), it.name) },
                cast = details.credits?.cast?.map { peopleFromCastCredit(it) } ?: listOf(),
                directors = directors,
                recommendations = recommendations,
            ).also {
                it.contentRating = contentRating
                it.logo = logo.takeIf { UserPreferences.enableTmdbLogos }
                it.logoLanguage = stampedLang
                it.logoSource = if (UserPreferences.enableTmdbLogos) {
                    com.dskja.betterstreamflix.logo.TmdbLogoPicker.inferSource(logo)
                } else {
                    com.dskja.betterstreamflix.logo.LogoSource.UNKNOWN
                }
            }
            TmdbCache.putMovie(
                TmdbCache.CachedMovie(
                    id = details.id,
                    title = result.title,
                    overview = result.overview,
                    released = details.releaseDate,
                    runtime = result.runtime,
                    trailer = result.trailer,
                    rating = result.rating,
                    poster = result.poster,
                    banner = result.banner,
                    imdbId = result.imdbId,
                    contentRating = contentRating,
                    logo = logo,
                    logoLanguage = stampedLang,
                    genres = result.genres.map { it.id to it.name },
                    cast = result.cast.map { Triple(it.id, it.name, it.image) },
                    directors = directors.map { Triple(it.id, it.name, it.image) },
                    recommendations = recommendations.mapNotNull { show ->
                        when (show) {
                            is Movie -> TmdbCache.CachedShowRef(
                                id = show.tmdbId?.toIntOrNull() ?: show.id.toIntOrNull() ?: return@mapNotNull null,
                                isTv = false,
                                title = show.title,
                                overview = show.overview,
                                released = show.released?.format("yyyy-MM-dd"),
                                rating = show.rating,
                                poster = show.poster,
                                banner = show.banner,
                            )
                            is TvShow -> TmdbCache.CachedShowRef(
                                id = show.tmdbId?.toIntOrNull() ?: show.id.toIntOrNull() ?: return@mapNotNull null,
                                isTv = true,
                                title = show.title,
                                overview = show.overview,
                                released = show.released?.format("yyyy-MM-dd"),
                                rating = show.rating,
                                poster = show.poster,
                                banner = show.banner,
                            )
                            else -> null
                        }
                    },
                ),
                language = lang,
            )
            result
        } catch (_: Exception) { null }
    }

    suspend fun getMovieByImdbId(imdbId: String, language: String? = null): Movie? {
        if (!UserPreferences.enableTmdb) return null
        val clean = imdbId.trim()
        if (clean.isBlank()) return null
        if (TmdbCache.hasFindImdbMovie(clean)) {
            val id = TmdbCache.getFindImdbMovie(clean) ?: return null
            return getMovieById(id, language)
        }
        return try {
            val found = TMDb3.Find.byImdbId(clean, language).movieResults.firstOrNull()
            TmdbCache.putFindImdbMovie(clean, found?.id)
            found?.let { getMovieById(it.id, language) }
        } catch (_: Exception) {
            TmdbCache.putFindImdbMovie(clean, null)
            null
        }
    }

    suspend fun getTvShow(title: String, year: Int? = null, language: String? = null): TvShow? {
        if (!UserPreferences.enableTmdb) return null
        return try {
            val effectiveYear = year ?: extractYear(title)
            val cacheKey = buildLookupCacheKey("tv-match", title, effectiveYear, language)
            if (TmdbCache.hasSearchTv(cacheKey)) {
                val cachedId = TmdbCache.getSearchTvId(cacheKey) ?: return null
                return getTvShowById(cachedId, language)
            }
            val tv = findBestTvMatch(title, effectiveYear, language)
            TmdbCache.putSearchTvId(cacheKey, tv?.id)
            tv?.let { getTvShowById(it.id, language) }
        } catch (_: Exception) { null }
    }

    suspend fun getTvShowById(tmdbId: Int, language: String? = null): TvShow? {
        if (!UserPreferences.enableTmdb) return null
        val lang = language ?: UserPreferences.currentProvider?.language
        TmdbCache.getTv(tmdbId, lang)?.let { cached ->
            return TvShow(
                id = cached.id.toString(),
                title = cached.title,
                overview = cached.overview,
                released = cached.released,
                trailer = cached.trailer,
                rating = cached.rating,
                poster = cached.poster,
                banner = cached.banner,
                imdbId = cached.imdbId,
                tmdbId = cached.id.toString(),
                seasons = cached.seasons.map {
                    Season(
                        id = "${cached.id}-${it.number}",
                        number = it.number,
                        title = it.title,
                        poster = it.poster,
                    )
                },
                genres = cached.genres.map { Genre(it.first, it.second) },
                cast = cached.cast.map { People(it.first, it.second, it.third) },
                directors = cached.directors.map { People(it.first, it.second, it.third) },
                recommendations = cached.recommendations.map { it.toShow() },
            ).also { tvShow ->
                tvShow.contentRating = cached.contentRating
                tvShow.logo = cached.logo
                    ?.takeIf { UserPreferences.enableTmdbLogos }
                    ?.takeUnless { url -> TmdbLogoCache.isBlacklisted(url) }
                tvShow.logoLanguage = when {
                    !UserPreferences.enableTmdbLogos || tvShow.logo.isNullOrBlank() -> null
                    !cached.logoLanguage.isNullOrBlank() -> cached.logoLanguage
                    else -> lang
                }
                tvShow.logoSource = if (UserPreferences.enableTmdbLogos) {
                    com.dskja.betterstreamflix.logo.TmdbLogoPicker.inferSource(tvShow.logo)
                } else {
                    com.dskja.betterstreamflix.logo.LogoSource.UNKNOWN
                }
            }
        }
        return try {
            val details = TMDb3.TvSeries.details(
                seriesId = tmdbId,
                appendToResponse = listOf(
                    TMDb3.Params.AppendToResponse.Tv.CREDITS,
                    TMDb3.Params.AppendToResponse.Tv.RECOMMENDATIONS,
                    TMDb3.Params.AppendToResponse.Tv.VIDEOS,
                    TMDb3.Params.AppendToResponse.Tv.EXTERNAL_IDS,
                    TMDb3.Params.AppendToResponse.Tv.CONTENT_RATING,
                    TMDb3.Params.AppendToResponse.Tv.IMAGES,
                ),
                language = lang,
                includeImageLanguage = includeLanguageList(lang),
                includeVideoLanguage = includeLanguageList(lang),
            )
            val directors = details.credits?.crew
                ?.filter {
                    it.job.equals("Director", ignoreCase = true) ||
                        it.job.equals("Series Director", ignoreCase = true)
                }
                ?.distinctBy { it.id }
                ?.map { People(it.id.toString(), it.name, it.profilePath?.w500) }
                .orEmpty()
                .ifEmpty {
                    details.createdBy?.map { People(it.id.toString(), it.name, it.profilePath?.w500) }
                        .orEmpty()
                }
            val recommendations = mapRecommendations(details.recommendations?.results)
            val contentRating = extractTvCertification(details, lang)
            val previousLogo = TmdbCache.getTv(tmdbId, lang)?.logo
            val logo = if (UserPreferences.enableTmdbLogos) {
                val picked = pickBestLogo(details.images?.logos, lang)
                val candidates = details.images?.logos?.map {
                    TmdbLogoPicker.LogoCandidate(
                        filePath = it.filePath,
                        iso639 = it.iso639,
                        voteCount = it.voteCount,
                        voteAverage = it.voteAverage,
                        width = it.width,
                        height = it.height,
                    )
                }
                if (picked != null || TmdbLogoFetch.shouldRememberMiss(candidates)) {
                    TmdbLogoCache.put(logoCacheKey(tmdbId, lang), picked)
                }
                picked
            } else {
                previousLogo
            }
            val stampedLang = lang.takeIf {
                UserPreferences.enableTmdbLogos && !logo.isNullOrBlank()
            }
            val result = TvShow(
                id = details.id.toString(),
                title = details.name,
                overview = details.overview,
                released = details.firstAirDate,
                trailer = pickBestYoutubeTrailerUrl(details.videos?.results),
                rating = details.voteAverage.toDouble(),
                poster = details.posterPath?.original,
                banner = details.backdropPath?.original,
                imdbId = details.externalIds?.imdbId,
                tmdbId = details.id.toString(),
                seasons = details.seasons.map {
                    Season(
                        id = "${details.id}-${it.seasonNumber}",
                        number = it.seasonNumber,
                        title = it.name,
                        poster = it.posterPath?.w500,
                    )
                },
                genres = details.genres.map { Genre(it.id.toString(), it.name) },
                cast = details.credits?.cast?.map { peopleFromCastCredit(it) } ?: listOf(),
                directors = directors,
                recommendations = recommendations,
            ).also {
                it.contentRating = contentRating
                it.logo = logo.takeIf { UserPreferences.enableTmdbLogos }
                it.logoLanguage = stampedLang
                it.logoSource = if (UserPreferences.enableTmdbLogos) {
                    com.dskja.betterstreamflix.logo.TmdbLogoPicker.inferSource(logo)
                } else {
                    com.dskja.betterstreamflix.logo.LogoSource.UNKNOWN
                }
            }
            TmdbCache.putTv(
                TmdbCache.CachedTv(
                    id = details.id,
                    title = result.title,
                    overview = result.overview,
                    released = details.firstAirDate,
                    trailer = result.trailer,
                    rating = result.rating,
                    poster = result.poster,
                    banner = result.banner,
                    imdbId = result.imdbId,
                    contentRating = contentRating,
                    logo = logo,
                    logoLanguage = stampedLang,
                    seasons = result.seasons.map {
                        TmdbCache.SeasonCache(it.number, it.title, it.poster)
                    },
                    genres = result.genres.map { it.id to it.name },
                    cast = result.cast.map { Triple(it.id, it.name, it.image) },
                    directors = directors.map { Triple(it.id, it.name, it.image) },
                    recommendations = recommendations.mapNotNull { show ->
                        when (show) {
                            is Movie -> TmdbCache.CachedShowRef(
                                id = show.tmdbId?.toIntOrNull() ?: show.id.toIntOrNull() ?: return@mapNotNull null,
                                isTv = false,
                                title = show.title,
                                overview = show.overview,
                                released = show.released?.format("yyyy-MM-dd"),
                                rating = show.rating,
                                poster = show.poster,
                                banner = show.banner,
                            )
                            is TvShow -> TmdbCache.CachedShowRef(
                                id = show.tmdbId?.toIntOrNull() ?: show.id.toIntOrNull() ?: return@mapNotNull null,
                                isTv = true,
                                title = show.title,
                                overview = show.overview,
                                released = show.released?.format("yyyy-MM-dd"),
                                rating = show.rating,
                                poster = show.poster,
                                banner = show.banner,
                            )
                            else -> null
                        }
                    },
                ),
                language = lang,
            )
            result
        } catch (_: Exception) { null }
    }

    /**
     * Fills gaps on a provider movie for high-end detail pages:
     * directors, cast, recommendations, trailer, content rating, artwork.
     * Preserves provider ids and already-populated fields.
     * Prefers TMDb artwork when the provider only left a blank or placeholder.
     */
    suspend fun enrichMovieDetail(movie: Movie, language: String? = null): Movie {
        if (!UserPreferences.enableTmdb) return movie
        val lang = language ?: UserPreferences.currentProvider?.language
        val year = movie.released?.format("yyyy")?.toIntOrNull()
        val tmdb = when {
            !movie.tmdbId.isNullOrBlank() -> movie.tmdbId!!.toIntOrNull()?.let { getMovieById(it, lang) }
            !movie.imdbId.isNullOrBlank() -> getMovieByImdbId(movie.imdbId!!, lang)
            else -> null
        } ?: getMovie(movie.title, year = year, language = lang) ?: return movie

        return movie.copy(
            overview = movie.overview?.takeIf { it.isNotBlank() } ?: tmdb.overview,
            runtime = movie.runtime ?: tmdb.runtime,
            trailer = movie.trailer?.takeIf { it.isNotBlank() } ?: tmdb.trailer,
            rating = movie.rating ?: tmdb.rating,
            poster = movie.poster?.takeIf { it.isNotBlank() } ?: tmdb.poster,
            banner = movie.banner?.takeIf { it.isNotBlank() } ?: tmdb.banner,
            imdbId = movie.imdbId ?: tmdb.imdbId,
            genres = movie.genres.ifEmpty { tmdb.genres },
            directors = movie.directors.ifEmpty { tmdb.directors },
            cast = movie.cast.ifEmpty { tmdb.cast },
            recommendations = movie.recommendations.ifEmpty { tmdb.recommendations },
        ).apply {
            tmdbId = movie.tmdbId ?: tmdb.tmdbId
            contentRating = movie.contentRating ?: tmdb.contentRating
            providerName = movie.providerName
            isFavorite = movie.isFavorite
            isWatched = movie.isWatched
            watchedDate = movie.watchedDate
            lastPlayedAtMillis = movie.lastPlayedAtMillis
            watchHistory = movie.watchHistory
            logo = if (UserPreferences.enableTmdbLogos) {
                val tmdbLogo = tmdb.logo
                    ?: tmdbId?.toIntOrNull()?.let { getMovieLogo(it, lang) }
                TmdbLogoPicker.preferResolvedLogo(
                    current = movie.logo,
                    tmdb = tmdbLogo,
                    currentLang = movie.logoLanguage,
                    wantedLang = lang,
                )
            } else {
                movie.logo
            }
            // Stamp language only when the resolved logo is a TMDb pick (not provider).
            logoLanguage = when {
                !UserPreferences.enableTmdbLogos || logo.isNullOrBlank() -> null
                TmdbLogoPicker.isTrustedTmdbLogo(logo) -> lang
                else -> movie.logoLanguage
            }
            logoSource = if (UserPreferences.enableTmdbLogos) {
                com.dskja.betterstreamflix.logo.TmdbLogoPicker.inferSource(logo)
            } else {
                com.dskja.betterstreamflix.logo.LogoSource.UNKNOWN
            }
        }
    }

    /**
     * Fills gaps on a provider TV show for high-end detail pages.
     * Does not replace seasons/episodes from the provider.
     */
    suspend fun enrichTvShowDetail(tvShow: TvShow, language: String? = null): TvShow {
        if (!UserPreferences.enableTmdb) return tvShow
        val lang = language ?: UserPreferences.currentProvider?.language
        val year = tvShow.released?.format("yyyy")?.toIntOrNull()
        val tmdb = when {
            !tvShow.tmdbId.isNullOrBlank() -> tvShow.tmdbId!!.toIntOrNull()?.let { getTvShowById(it, lang) }
            !tvShow.imdbId.isNullOrBlank() -> getTvShowByImdbId(tvShow.imdbId!!, lang)
            else -> null
        } ?: getTvShow(tvShow.title, year = year, language = lang) ?: return tvShow

        return tvShow.copy(
            overview = tvShow.overview?.takeIf { it.isNotBlank() } ?: tmdb.overview,
            runtime = tvShow.runtime ?: tmdb.runtime,
            trailer = tvShow.trailer?.takeIf { it.isNotBlank() } ?: tmdb.trailer,
            rating = tvShow.rating ?: tmdb.rating,
            poster = tvShow.poster?.takeIf { it.isNotBlank() } ?: tmdb.poster,
            banner = tvShow.banner?.takeIf { it.isNotBlank() } ?: tmdb.banner,
            imdbId = tvShow.imdbId ?: tmdb.imdbId,
            genres = tvShow.genres.ifEmpty { tmdb.genres },
            directors = tvShow.directors.ifEmpty { tmdb.directors },
            cast = tvShow.cast.ifEmpty { tmdb.cast },
            recommendations = tvShow.recommendations.ifEmpty { tmdb.recommendations },
            seasons = tvShow.seasons,
        ).apply {
            tmdbId = tvShow.tmdbId ?: tmdb.tmdbId
            contentRating = tvShow.contentRating ?: tmdb.contentRating
            providerName = tvShow.providerName
            isFavorite = tvShow.isFavorite
            lastPlayedAtMillis = tvShow.lastPlayedAtMillis
            lastPlayedEpisodeId = tvShow.lastPlayedEpisodeId
            lastPlayedEpisode = tvShow.lastPlayedEpisode
            logo = if (UserPreferences.enableTmdbLogos) {
                val tmdbLogo = tmdb.logo
                    ?: tmdbId?.toIntOrNull()?.let { getTvShowLogo(it, lang) }
                TmdbLogoPicker.preferResolvedLogo(
                    current = tvShow.logo,
                    tmdb = tmdbLogo,
                    currentLang = tvShow.logoLanguage,
                    wantedLang = lang,
                )
            } else {
                tvShow.logo
            }
            logoLanguage = when {
                !UserPreferences.enableTmdbLogos || logo.isNullOrBlank() -> null
                TmdbLogoPicker.isTrustedTmdbLogo(logo) -> lang
                else -> tvShow.logoLanguage
            }
            logoSource = if (UserPreferences.enableTmdbLogos) {
                com.dskja.betterstreamflix.logo.TmdbLogoPicker.inferSource(logo)
            } else {
                com.dskja.betterstreamflix.logo.LogoSource.UNKNOWN
            }
        }
    }

    suspend fun getTvShowByImdbId(imdbId: String, language: String? = null): TvShow? {
        if (!UserPreferences.enableTmdb) return null
        val clean = imdbId.trim()
        if (clean.isBlank()) return null
        if (TmdbCache.hasFindImdbTv(clean)) {
            val id = TmdbCache.getFindImdbTv(clean) ?: return null
            return getTvShowById(id, language)
        }
        return try {
            val found = TMDb3.Find.byImdbId(clean, language).tvResults.firstOrNull()
            TmdbCache.putFindImdbTv(clean, found?.id)
            found?.let { getTvShowById(it.id, language) }
        } catch (_: Exception) {
            TmdbCache.putFindImdbTv(clean, null)
            null
        }
    }

    suspend fun getEpisodesBySeason(tvShowId: String, seasonNumber: Int, language: String? = null): List<Episode> {
        if (!UserPreferences.enableTmdb) return listOf()
        return try {
            TMDb3.TvSeasons.details(
                seriesId = tvShowId.toInt(),
                seasonNumber = seasonNumber,
                language = language
            ).episodes?.map {
                Episode(
                    id = it.id.toString(),
                    number = it.episodeNumber,
                    title = it.name ?: "",
                    released = it.airDate,
                    poster = it.stillPath?.w500,
                    overview = it.overview,
                )
            } ?: listOf()
        } catch (_: Exception) { listOf() }
    }

    suspend fun getMovieAgeRating(title: String, year: Int? = null, language: String? = null): Int? {
        if (!UserPreferences.enableTmdb) return null

        val effectiveYear = year ?: extractYear(title)
        val cacheKey = buildLookupCacheKey("movie", title, effectiveYear, language)
        movieAgeCache[cacheKey]?.let(::decodeAgeRatingCacheValue)?.let { return it }
        if (movieAgeCache.containsKey(cacheKey)) return null

        val ageRating = runCatching {
            val movie = findBestMovieMatch(title, effectiveYear, language) ?: return@runCatching null
            getMovieAgeRatingById(movie.id, language)
        }.getOrNull()

        movieAgeCache[cacheKey] = encodeAgeRatingCacheValue(ageRating)
        return ageRating
    }

    suspend fun getTvShowAgeRating(title: String, year: Int? = null, language: String? = null): Int? {
        if (!UserPreferences.enableTmdb) return null

        val effectiveYear = year ?: extractYear(title)
        val cacheKey = buildLookupCacheKey("tv", title, effectiveYear, language)
        tvAgeCache[cacheKey]?.let(::decodeAgeRatingCacheValue)?.let { return it }
        if (tvAgeCache.containsKey(cacheKey)) return null

        val ageRating = runCatching {
            val tvShow = findBestTvMatch(title, effectiveYear, language) ?: return@runCatching null
            getTvShowAgeRatingById(tvShow.id, language)
        }.getOrNull()

        tvAgeCache[cacheKey] = encodeAgeRatingCacheValue(ageRating)
        return ageRating
    }

    suspend fun getMovieAgeRatingById(id: Int, language: String? = null): Int? {
        if (!UserPreferences.enableTmdb) return null

        val cacheKey = "movie-id|$id|${language.orEmpty()}"
        movieAgeCache[cacheKey]?.let(::decodeAgeRatingCacheValue)?.let { return it }
        if (movieAgeCache.containsKey(cacheKey)) return null

        val ageRating = runCatching {
            val details = TMDb3.Movies.details(
                movieId = id,
                appendToResponse = listOf(TMDb3.Params.AppendToResponse.Movie.RELEASES_DATES),
                language = language
            )
            extractMovieAgeRating(details, language)
        }.getOrNull()

        movieAgeCache[cacheKey] = encodeAgeRatingCacheValue(ageRating)
        return ageRating
    }

    suspend fun getTvShowAgeRatingById(id: Int, language: String? = null): Int? {
        if (!UserPreferences.enableTmdb) return null

        val cacheKey = "tv-id|$id|${language.orEmpty()}"
        tvAgeCache[cacheKey]?.let(::decodeAgeRatingCacheValue)?.let { return it }
        if (tvAgeCache.containsKey(cacheKey)) return null

        val ageRating = runCatching {
            val details = TMDb3.TvSeries.details(
                seriesId = id,
                appendToResponse = listOf(TMDb3.Params.AppendToResponse.Tv.CONTENT_RATING),
                language = language
            )
            extractTvShowAgeRating(details, language)
        }.getOrNull()

        tvAgeCache[cacheKey] = encodeAgeRatingCacheValue(ageRating)
        return ageRating
    }

    private suspend fun findBestMovieMatch(
        rawTitle: String,
        year: Int?,
        language: String?,
    ): TMDb3.Movie? {
        val results = searchMovieCandidates(rawTitle, year, language)
        val scoredResults = results.map { movie ->
            movie to scoreCandidate(
                candidateTitles = listOf(movie.title, movie.originalTitle),
                queryTitle = rawTitle,
                year = year,
                candidateYear = extractYear(movie.releaseDate),
            )
        }

        val bestSearchMatch = scoredResults.maxByOrNull { it.second }
        val minScore = minAcceptableScore(year)
        if (bestSearchMatch != null && bestSearchMatch.second >= minScore) {
            return bestSearchMatch.first
        }

        val localizedFallback = findBestLocalizedMovieCandidate(
            rawTitle = rawTitle,
            year = year,
            language = language,
            candidates = scoredResults
                .sortedByDescending { it.second }
                .map { it.first }
                .take(MAX_LOCALIZED_DETAIL_CANDIDATES),
        )
        if (localizedFallback != null) return localizedFallback

        return bestSearchMatch
            ?.takeIf { it.second >= minScore }
            ?.first
    }

    private suspend fun findBestTvMatch(
        rawTitle: String,
        year: Int?,
        language: String?,
    ): TMDb3.Tv? {
        val results = searchTvCandidates(rawTitle, year, language)
        val scoredResults = results.map { tv ->
            tv to scoreCandidate(
                candidateTitles = listOf(tv.name, tv.originalName),
                queryTitle = rawTitle,
                year = year,
                candidateYear = extractYear(tv.firstAirDate),
            )
        }

        val bestSearchMatch = scoredResults.maxByOrNull { it.second }
        val minScore = minAcceptableScore(year)
        if (bestSearchMatch != null && bestSearchMatch.second >= minScore) {
            return bestSearchMatch.first
        }

        val localizedFallback = findBestLocalizedTvCandidate(
            rawTitle = rawTitle,
            year = year,
            language = language,
            candidates = scoredResults
                .sortedByDescending { it.second }
                .map { it.first }
                .take(MAX_LOCALIZED_DETAIL_CANDIDATES),
        )
        if (localizedFallback != null) return localizedFallback

        return bestSearchMatch
            ?.takeIf { it.second >= minScore }
            ?.first
    }

    private suspend fun searchMovieCandidates(
        rawTitle: String,
        year: Int?,
        language: String?,
    ): List<TMDb3.Movie> {
        val variants = buildTitleVariants(rawTitle)
        val languages = listOfNotNull(language).plus(null).distinct()

        return languages
            .flatMap { searchLanguage ->
                variants.flatMap { query ->
                    buildList {
                        // Year-scoped movie search first for franchise disambiguation.
                        if (year != null) {
                            runCatching {
                                addAll(
                                    TMDb3.Search.movie(
                                        query = query,
                                        language = searchLanguage,
                                        primaryReleaseYear = year,
                                    ).results,
                                )
                            }
                        }
                        runCatching {
                            addAll(TMDb3.Search.movie(query = query, language = searchLanguage).results)
                        }
                        runCatching {
                            addAll(
                                TMDb3.Search.multi(query, language = searchLanguage)
                                    .results
                                    .filterIsInstance<TMDb3.Movie>(),
                            )
                        }
                    }
                }
            }
            .distinctBy { it.id }
    }

    private suspend fun searchTvCandidates(
        rawTitle: String,
        year: Int?,
        language: String?,
    ): List<TMDb3.Tv> {
        val variants = buildTitleVariants(rawTitle)
        val languages = listOfNotNull(language).plus(null).distinct()

        return languages
            .flatMap { searchLanguage ->
                variants.flatMap { query ->
                    buildList {
                        if (year != null) {
                            runCatching {
                                addAll(
                                    TMDb3.Search.tv(
                                        query = query,
                                        language = searchLanguage,
                                        firstAirDateYear = year,
                                    ).results,
                                )
                            }
                        }
                        runCatching {
                            addAll(TMDb3.Search.tv(query = query, language = searchLanguage).results)
                        }
                        runCatching {
                            addAll(
                                TMDb3.Search.multi(query, language = searchLanguage)
                                    .results
                                    .filterIsInstance<TMDb3.Tv>(),
                            )
                        }
                    }
                }
            }
            .distinctBy { it.id }
    }

    private suspend fun findBestLocalizedMovieCandidate(
        rawTitle: String,
        year: Int?,
        language: String?,
        candidates: List<TMDb3.Movie>,
    ): TMDb3.Movie? {
        if (language.isNullOrBlank() || candidates.isEmpty()) return null

        return candidates
            .mapNotNull { movie ->
                val details = runCatching {
                    TMDb3.Movies.details(
                        movieId = movie.id,
                        appendToResponse = listOf(TMDb3.Params.AppendToResponse.Movie.ALTERNATIVE_TITLES),
                        language = language,
                    )
                }.getOrNull() ?: return@mapNotNull null

                val score = scoreCandidate(
                    candidateTitles = listOf(details.title, details.originalTitle),
                    queryTitle = rawTitle,
                    year = year,
                    candidateYear = extractYear(details.releaseDate),
                )
                movie to score
            }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= minAcceptableScore(year) }
            ?.first
    }

    private suspend fun findBestLocalizedTvCandidate(
        rawTitle: String,
        year: Int?,
        language: String?,
        candidates: List<TMDb3.Tv>,
    ): TMDb3.Tv? {
        if (language.isNullOrBlank() || candidates.isEmpty()) return null

        return candidates
            .mapNotNull { tv ->
                val details = runCatching {
                    TMDb3.TvSeries.details(
                        seriesId = tv.id,
                        appendToResponse = listOf(TMDb3.Params.AppendToResponse.Tv.ALTERNATIVE_TITLES),
                        language = language,
                    )
                }.getOrNull() ?: return@mapNotNull null

                val score = scoreCandidate(
                    candidateTitles = listOf(details.name, details.originalName),
                    queryTitle = rawTitle,
                    year = year,
                    candidateYear = extractYear(details.firstAirDate),
                )
                tv to score
            }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= minAcceptableScore(year) }
            ?.first
    }

    private fun minAcceptableScore(year: Int?): Int =
        if (year != null) MIN_ACCEPTABLE_SCORE_WITH_YEAR else MIN_ACCEPTABLE_SCORE

    private fun scoreCandidate(
        candidateTitles: List<String?>,
        queryTitle: String,
        year: Int?,
        candidateYear: Int?,
    ): Int {
        val queryVariants = buildTitleVariants(queryTitle).map(::normalizeTitle)
        val candidateVariants = candidateTitles
            .filterNotNull()
            .map(::normalizeTitle)
            .distinct()

        val titleScore = candidateVariants.maxOfOrNull { candidate ->
            queryVariants.maxOfOrNull { query ->
                when {
                    candidate == query -> 120
                    candidate.replace(" ", "") == query.replace(" ", "") -> 110
                    candidate.startsWith(query) || query.startsWith(candidate) -> 85
                    candidate.contains(query) || query.contains(candidate) -> WEAK_CONTAINS_SCORE
                    overlapScore(candidate, query) >= 0.8 -> 55
                    else -> 0
                }
            } ?: 0
        } ?: 0

        // When the query has no year, don't accept pure substring matches alone.
        if (year == null && titleScore == WEAK_CONTAINS_SCORE) {
            return titleScore - 15
        }

        val yearScore = when {
            year == null || candidateYear == null -> 0
            year == candidateYear -> 20
            max(year, candidateYear) - minOf(year, candidateYear) == 1 -> 5
            else -> -50
        }

        // Query title includes a year: reject franchise mismatches (year off by >1).
        if (year != null && candidateYear != null && yearScore < 0) {
            return (titleScore + yearScore).coerceAtMost(MIN_ACCEPTABLE_SCORE - 1)
        }

        // Query has a year but candidate has none: require a strong title match.
        if (year != null && candidateYear == null && titleScore < 110) {
            return titleScore.coerceAtMost(MIN_ACCEPTABLE_SCORE - 1)
        }

        return titleScore + yearScore
    }

    private fun buildTitleVariants(title: String): List<String> {
        val trimmed = title.trim()
        val withoutTrailingYear = trimmed
            .replace(Regex("\\s*\\((19|20)\\d{2}\\)\\s*$"), "")
            .replace(Regex("\\s*\\[(19|20)\\d{2}]\\s*$"), "")
            .trim()
        val withoutDecorators = withoutTrailingYear
            .replace(Regex("\\s*[\\-–:]\\s*(sub|dub|ita|ger|de|eng|en)\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
        // SerienStream / DE catalogue often appends "Staffel N" to TV titles.
        val withoutSeason = withoutDecorators
            .replace(Regex("\\s*Staffel\\s+\\d+\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*Season\\s+\\d+\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*S\\d{1,2}\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*(episode|ep|part|pt)\\.?\\s*\\d+\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
        val normalized = titleNormalizer(withoutSeason)

        return listOf(trimmed, withoutTrailingYear, withoutDecorators, withoutSeason, normalized)
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun extractYear(value: String?): Int? {
        return value
            ?.let { Regex("(19|20)\\d{2}").find(it)?.value }
            ?.toIntOrNull()
    }

    /**
     * Strips punctuation, leading "the", and trailing episode/season junk so
     * catalogue titles like "One Last Stick" / "The X: Episode 2" still match TMDb.
     */
    fun titleNormalizer(value: String): String {
        val ascii = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return ascii
            .lowercase()
            .replace("&", " and ")
            .replace(Regex("\\s*\\((19|20)\\d{2}\\)\\s*"), " ")
            .replace(Regex("\\s*(staffel|season)\\s+\\d+\\b"), " ")
            .replace(Regex("\\s*(episode|ep|part|pt)\\.?\\s*\\d+\\b"), " ")
            .replace(Regex("\\bs\\d{1,2}e?\\d{0,2}\\b"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("^the\\s+"), "")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun normalizeTitle(value: String): String = titleNormalizer(value)

    private fun overlapScore(left: String, right: String): Double {
        val leftWords = left.split(" ").filter { it.isNotBlank() }.toSet()
        val rightWords = right.split(" ").filter { it.isNotBlank() }.toSet()
        if (leftWords.isEmpty() || rightWords.isEmpty()) return 0.0

        val overlap = leftWords.intersect(rightWords).size.toDouble()
        return overlap / max(leftWords.size, rightWords.size).toDouble()
    }

    private fun buildLookupCacheKey(type: String, title: String, year: Int?, language: String?): String {
        return "$type|${normalizeTitle(title)}|${year ?: -1}|${language.orEmpty()}"
    }

    private fun encodeAgeRatingCacheValue(ageRating: Int?): Int = ageRating ?: UNKNOWN_AGE_RATING

    private fun decodeAgeRatingCacheValue(value: Int): Int? {
        return value.takeUnless { it == UNKNOWN_AGE_RATING }
    }

    private fun extractMovieCertification(details: TMDb3.Movie.Detail, language: String?): String? {
        val releaseDates = details.releaseDates?.results.orEmpty()
        buildPreferredCertificationCountries(language).forEach { countryCode ->
            releaseDates
                .firstOrNull { it.iso3166.equals(countryCode, ignoreCase = true) }
                ?.releaseDates
                ?.mapNotNull { it.certification?.trim()?.takeIf { c -> c.isNotEmpty() } }
                ?.firstOrNull()
                ?.let { return it }
        }
        return releaseDates
            .asSequence()
            .flatMap { it.releaseDates.asSequence() }
            .mapNotNull { it.certification?.trim()?.takeIf { c -> c.isNotEmpty() } }
            .firstOrNull()
    }

    private fun extractTvCertification(details: TMDb3.Tv.Detail, language: String?): String? {
        val contentRatings = details.contentRatings?.results.orEmpty()
        buildPreferredCertificationCountries(language).forEach { countryCode ->
            contentRatings
                .firstOrNull { it.iso3166.equals(countryCode, ignoreCase = true) }
                ?.rating?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        return contentRatings
            .asSequence()
            .mapNotNull { it.rating?.trim()?.takeIf { r -> r.isNotEmpty() } }
            .firstOrNull()
    }

    private fun peopleFromCastCredit(cast: TMDb3.Cast): People =
        People(
            id = cast.id.toString(),
            name = cast.name,
            image = cast.profilePath?.w500,
        ).also { person ->
            person.character = cast.character.takeIf { it.isNotBlank() }
        }

    private fun mapRecommendations(results: List<TMDb3.MultiItem>?): List<Show> {
        if (results.isNullOrEmpty()) return emptyList()
        return results.mapNotNull { multi ->
            when (multi) {
                is TMDb3.Movie -> Movie(
                    id = multi.id.toString(),
                    title = multi.title,
                    overview = multi.overview,
                    released = multi.releaseDate,
                    rating = multi.voteAverage.toDouble(),
                    poster = multi.posterPath?.w500,
                    banner = multi.backdropPath?.original,
                    tmdbId = multi.id.toString(),
                )
                is TMDb3.Tv -> TvShow(
                    id = multi.id.toString(),
                    title = multi.name,
                    overview = multi.overview,
                    released = multi.firstAirDate,
                    rating = multi.voteAverage.toDouble(),
                    poster = multi.posterPath?.w500,
                    banner = multi.backdropPath?.original,
                    tmdbId = multi.id.toString(),
                )
                else -> null
            }
        }.take(20)
    }

    private fun TmdbCache.CachedShowRef.toShow(): Show =
        if (isTv) {
            TvShow(
                id = id.toString(),
                title = title,
                overview = overview,
                released = released,
                rating = rating,
                poster = poster,
                banner = banner,
                tmdbId = id.toString(),
            )
        } else {
            Movie(
                id = id.toString(),
                title = title,
                overview = overview,
                released = released,
                rating = rating,
                poster = poster,
                banner = banner,
                tmdbId = id.toString(),
            )
        }

    private fun extractMovieAgeRating(details: TMDb3.Movie.Detail, language: String?): Int? {
        val releaseDates = details.releaseDates?.results.orEmpty()
        val preferredCountries = buildPreferredCertificationCountries(language)

        preferredCountries.forEach { countryCode ->
            releaseDates
                .firstOrNull { it.iso3166.equals(countryCode, ignoreCase = true) }
                ?.releaseDates
                ?.mapNotNull { parseAgeRating(it.certification) }
                ?.maxOrNull()
                ?.let { return it }
        }

        return releaseDates
            .asSequence()
            .flatMap { it.releaseDates.asSequence() }
            .mapNotNull { parseAgeRating(it.certification) }
            .maxOrNull()
    }

    private fun extractTvShowAgeRating(details: TMDb3.Tv.Detail, language: String?): Int? {
        val contentRatings = details.contentRatings?.results.orEmpty()
        val preferredCountries = buildPreferredCertificationCountries(language)

        preferredCountries.forEach { countryCode ->
            contentRatings
                .filter { it.iso3166.equals(countryCode, ignoreCase = true) }
                .mapNotNull { parseAgeRating(it.rating) }
                .maxOrNull()
                ?.let { return it }
        }

        return contentRatings
            .mapNotNull { parseAgeRating(it.rating) }
            .maxOrNull()
    }

    private fun buildPreferredCertificationCountries(language: String?): List<String> {
        val primary = when (language?.substringBefore('-')?.lowercase()) {
            "de" -> "DE"
            "es" -> "ES"
            "fr" -> "FR"
            "it" -> "IT"
            "pt" -> "PT"
            "en" -> "US"
            else -> language
                ?.substringAfterLast('-')
                ?.takeIf { it.length == 2 }
                ?.uppercase()
        }

        return listOfNotNull(primary, "US", "GB", "DE", "FR", "ES", "IT", "PT")
            .distinct()
    }

    private fun parseAgeRating(raw: String?): Int? {
        val normalized = raw
            ?.trim()
            ?.uppercase()
            ?.replace('_', '-')
            ?.replace(Regex("\\s+"), " ")
            ?: return null

        if (normalized.isBlank()) return null

        listOf(18, 17, 16, 15, 14, 13, 12, 10, 7, 6, 0).forEach { age ->
            if (Regex("(^|\\D)$age(\\D|$)").containsMatchIn(normalized)) {
                return age
            }
        }

        return when (normalized) {
            "TV-MA", "MA", "MATURE", "X", "RX", "C" -> 18
            "NC-17", "R" -> 17
            "TV-14" -> 14
            "PG-13" -> 13
            "TV-PG", "PG" -> 10
            "TV-Y7", "TV-Y7-FV" -> 7
            "TV-G", "TV-Y", "G", "U", "A", "AL", "ATP", "TP", "L", "T", "ALL" -> 0
            "NR", "UR", "UNRATED", "NOT RATED", "N/A" -> null
            else -> when {
                normalized.contains("TOUS PUBLICS") -> 0
                normalized.contains("APTA") -> 0
                normalized.contains("TODOS") -> 0
                normalized.contains("MA") -> 17
                normalized.contains("PG") -> 10
                else -> null
            }
        }
    }
}
