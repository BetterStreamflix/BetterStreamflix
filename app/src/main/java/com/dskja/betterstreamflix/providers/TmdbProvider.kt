package com.dskja.betterstreamflix.providers

import com.dskja.betterstreamflix.BuildConfig
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.extractors.AfterDarkExtractor
import com.dskja.betterstreamflix.extractors.Extractor
import com.dskja.betterstreamflix.extractors.MoflixExtractor
import com.dskja.betterstreamflix.extractors.MoviesapiExtractor
import com.dskja.betterstreamflix.extractors.TwoEmbedExtractor
import com.dskja.betterstreamflix.extractors.VidsrcNetExtractor
import com.dskja.betterstreamflix.extractors.VidsrcToExtractor
import com.dskja.betterstreamflix.extractors.VidzeeExtractor
import com.dskja.betterstreamflix.extractors.VixSrcExtractor
import com.dskja.betterstreamflix.extractors.VidLinkExtractor
import com.dskja.betterstreamflix.extractors.VidsrcRuExtractor
import com.dskja.betterstreamflix.extractors.EinschaltenExtractor
import com.dskja.betterstreamflix.extractors.FrembedExtractor
import com.dskja.betterstreamflix.extractors.VidflixExtractor
import com.dskja.betterstreamflix.extractors.VidrockExtractor
import com.dskja.betterstreamflix.extractors.VideasyExtractor
import com.dskja.betterstreamflix.extractors.PrimeSrcExtractor
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Genre
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.People
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.utils.CatalogSortMode
import com.dskja.betterstreamflix.utils.TMDb3
import com.dskja.betterstreamflix.utils.TMDb3.original
import com.dskja.betterstreamflix.utils.TMDb3.w500
import com.dskja.betterstreamflix.utils.ProviderAudioLanguage
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.safeSubList
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request

class TmdbProvider private constructor(override val language: String) : Provider {
    companion object {
        private const val TMDB_DE_PREFIX = "tmdbde:"

        private val instances =
            java.util.concurrent.ConcurrentHashMap<String, TmdbProvider>()

        /**
         * Stable per-language instance. [UserPreferences.currentProvider] used to
         * allocate a fresh [TmdbProvider] on every read, so Home's
         * `provider != currentProvider` identity check always dropped Success and
         * left the TMDb Home spinner spinning forever.
         */
        fun forLanguage(language: String): TmdbProvider {
            val lang = com.dskja.betterstreamflix.utils.ProviderAudioLanguage
                .normalizeTmdbLanguage(language)
            return instances.getOrPut(lang) { TmdbProvider(lang) }
        }

        /** @deprecated Prefer [forLanguage]; kept for call-site compatibility. */
        operator fun invoke(language: String): TmdbProvider = forLanguage(language)
    }

    override val baseUrl: String
        get() = ""

    override val name = "TMDb ($language)"
    override val logo =
        "https://upload.wikimedia.org/wikipedia/commons/thumb/8/89/Tmdb.new.logo.svg/1280px-Tmdb.new.logo.svg.png"

    override fun equals(other: Any?): Boolean =
        other is TmdbProvider && other.language == language

    override fun hashCode(): Int = language.hashCode()

    override suspend fun getHome(): List<Category> {
        try {
            requireTmdbApiKey()
            return buildHomeCategories()
        } catch (e: ClassCastException) {
            // Production still reported BETTERSTREAMFLIX-Q — never hard-fail Home.
            Log.e("TmdbProvider", "TMDB home ClassCast soft-fail: ${e.message}", e)
            return emptyList()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (e.cause is ClassCastException) {
                Log.e("TmdbProvider", "TMDB home ClassCast (cause) soft-fail: ${e.message}", e)
                return emptyList()
            }
            Log.e("TmdbProvider", "TMDB home failed: ${e.message}", e)
            throw Exception(classifyTmdbFailure(e), e)
        }
    }

    private fun requireTmdbApiKey() {
        if (!UserPreferences.enableTmdb) {
            throw Exception(
                "TMDb metadata is disabled. Enable TMDb under Settings → Content, then try again.",
            )
        }
        if (!TMDb3.hasApiKey()) {
            throw Exception(
                "TMDb API key is missing. Open Settings → enter your TMDb API key " +
                    "(https://www.themoviedb.org/settings/api), then try again."
            )
        }
    }

    private fun classifyTmdbFailure(e: Exception): String {
        val message = e.message.orEmpty()
        val httpCode = (e as? retrofit2.HttpException)?.code()
        return when {
            message.contains("API key", ignoreCase = true) ||
                message.contains("Invalid API key", ignoreCase = true) ||
                httpCode == 401 ->
                "TMDb API key invalid or missing. Update it in Settings."
            httpCode == 404 ->
                "TMDb returned 404 for this request. Content may have been removed."
            httpCode == 429 ->
                "TMDb rate limit reached. Wait a moment and retry."
            e is java.net.UnknownHostException ||
                e is java.net.ConnectException ||
                message.contains("Unable to resolve host", ignoreCase = true) ||
                message.contains("failed to connect", ignoreCase = true) ->
                "TMDB is unreachable (api.themoviedb.org). " +
                    "Check your connection or switch DNS over HTTPS in Settings. (${e.message})"
            e is java.net.SocketTimeoutException ||
                message.contains("timeout", ignoreCase = true) ->
                "TMDb timed out. Check your connection and retry. (${e.message})"
            httpCode in 500..599 ->
                "TMDb server error ($httpCode). Retry shortly."
            else ->
                "TMDb request failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * Soft-await a shelf: timeout / network / ClassCast errors return [fallback]
     * so one bad TMDb endpoint cannot block the entire Home catalog.
     *
     * The block is a [CoroutineScope] receiver so nested [async] children belong
     * to this shelf scope (cancelled with the timeout). Capturing the outer Home
     * [coroutineScope] for nested async used to:
     * - leave orphan OkHttp work alive past the shelf budget (#206 / soft-fail), and
     * - let a child ClassCastException fail the outer Home scope even when the
     *   shelf catch returned fallback (BETTERSTREAMFLIX-Q).
     */
    private suspend fun <T> softShelf(
        label: String,
        fallback: T,
        timeoutMs: Long = ProviderSmoke.TMDB_SHELF_TIMEOUT_MS,
        block: suspend kotlinx.coroutines.CoroutineScope.() -> T,
    ): T {
        return try {
            withTimeout(timeoutMs) {
                coroutineScope { block() }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w("TmdbProvider", "TMDb shelf '$label' timed out after ${timeoutMs}ms")
            fallback
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(
                "TmdbProvider",
                "TMDb shelf '$label' failed: ${e.javaClass.simpleName}: ${e.message}",
            )
            fallback
        }
    }

    private suspend fun buildHomeCategories(): List<Category> = coroutineScope {
        val categories = mutableListOf<Category>()
        val watchRegion = if (language == "en") "US" else language.uppercase()

        val mapMulti: (TMDb3.MultiItem) -> AppAdapter.Item? = { multi ->
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
        }

        val mapMovie: (TMDb3.Movie) -> Movie = { m ->
            Movie(
                id = m.id.toString(),
                title = m.title,
                overview = m.overview,
                released = m.releaseDate,
                rating = m.voteAverage.toDouble(),
                poster = m.posterPath?.w500,
                banner = m.backdropPath?.original,
                tmdbId = m.id.toString(),
            )
        }
        val mapTv: (TMDb3.Tv) -> TvShow = { t ->
            TvShow(
                id = t.id.toString(),
                title = t.name,
                overview = t.overview,
                released = t.firstAirDate,
                rating = t.voteAverage.toDouble(),
                poster = t.posterPath?.w500,
                banner = t.backdropPath?.original,
                tmdbId = t.id.toString(),
            )
        }

        // One page per list — three pages × many shelves was a request storm that
        // hung Home when DNS/API stalled (callTimeout previously 60s each).
        val trendingDeferred = async {
            softShelf("trending", emptyList()) {
                // compactResults drops Gson nulls from incomplete media_type rows.
                TMDb3.Trending.all(TMDb3.Params.TimeWindow.DAY, page = 1, language = language)
                    .results
                    .filterNotNull()
            }
        }

        val popularMoviesDeferred = async {
            softShelf("popularMovies", emptyList()) {
                TMDb3.MovieLists.popular(page = 1, language = language).results
            }
        }

        val popularTvShowsDeferred = async {
            softShelf("popularTv", emptyList()) {
                TMDb3.TvSeriesLists.popular(page = 1, language = language).results
            }
        }

        val popularAnimeDeferred = async {
            softShelf("anime", emptyList()) {
                // Keep Movie/Tv lists typed separately — never force List<MultiItem>
                // via awaitAll/cast (BETTERSTREAMFLIX-Q ClassCast).
                val movies = async {
                    runCatching {
                        TMDb3.Discover.movie(
                            language = language,
                            withKeywords = TMDb3.Params.WithBuilder(TMDb3.Keyword.KeywordId.ANIME)
                                .or(TMDb3.Keyword.KeywordId.BASED_ON_ANIME),
                        ).results
                    }.getOrElse { e ->
                        Log.w("TmdbProvider", "TMDb anime movies failed: ${e.javaClass.simpleName}")
                        emptyList()
                    }
                }
                val shows = async {
                    runCatching {
                        TMDb3.Discover.tv(
                            language = language,
                            withKeywords = TMDb3.Params.WithBuilder(TMDb3.Keyword.KeywordId.ANIME)
                                .or(TMDb3.Keyword.KeywordId.BASED_ON_ANIME),
                        ).results
                    }.getOrElse { e ->
                        Log.w("TmdbProvider", "TMDb anime shows failed: ${e.javaClass.simpleName}")
                        emptyList()
                    }
                }
                movies.await() + shows.await()
            }
        }

        suspend fun streamingShelf(
            label: String,
            movieProvider: TMDb3.Provider.WatchProviderId,
            tvNetwork: TMDb3.Network.NetworkId,
        ): List<TMDb3.MultiItem> = softShelf(label, emptyList()) {
            val movies = async {
                runCatching {
                    TMDb3.Discover.movie(
                        language = language,
                        watchRegion = watchRegion,
                        withWatchProviders = TMDb3.Params.WithBuilder(movieProvider),
                    ).results
                }.getOrElse { e ->
                    Log.w("TmdbProvider", "TMDb $label movies failed: ${e.javaClass.simpleName}")
                    emptyList()
                }
            }
            val shows = async {
                runCatching {
                    TMDb3.Discover.tv(
                        language = language,
                        withNetworks = TMDb3.Params.WithBuilder(tvNetwork),
                    ).results
                }.getOrElse { e ->
                    Log.w("TmdbProvider", "TMDb $label shows failed: ${e.javaClass.simpleName}")
                    emptyList()
                }
            }
            // List<Movie> + List<Tv> widens to List<MultiItem> without unsafe casts.
            movies.await() + shows.await()
        }

        val netflixDeferred = async {
            streamingShelf("netflix", TMDb3.Provider.WatchProviderId.NETFLIX, TMDb3.Network.NetworkId.NETFLIX)
        }
        val amazonDeferred = async {
            streamingShelf("amazon", TMDb3.Provider.WatchProviderId.AMAZON_VIDEO, TMDb3.Network.NetworkId.AMAZON)
        }
        val disneyDeferred = async {
            streamingShelf("disney", TMDb3.Provider.WatchProviderId.DISNEY_PLUS, TMDb3.Network.NetworkId.DISNEY_PLUS)
        }
        val huluDeferred = async {
            streamingShelf("hulu", TMDb3.Provider.WatchProviderId.HULU, TMDb3.Network.NetworkId.HULU)
        }
        val appleDeferred = async {
            streamingShelf("apple", TMDb3.Provider.WatchProviderId.APPLE_TV_PLUS, TMDb3.Network.NetworkId.APPLE_TV)
        }

        val hboDeferred = async {
            softShelf("hbo", emptyList()) {
                TMDb3.Discover.tv(
                    language = language,
                    withNetworks = TMDb3.Params.WithBuilder(TMDb3.Network.NetworkId.HBO),
                    page = 1,
                ).results
            }
        }

        val topRatedMoviesDeferred = async {
            softShelf("topRatedMovies", emptyList()) {
                TMDb3.MovieLists.topRated(mapOf("language" to language, "page" to "1")).results
            }
        }
        val topRatedTvDeferred = async {
            softShelf("topRatedTv", emptyList()) {
                TMDb3.TvSeriesLists.topRated(mapOf("language" to language, "page" to "1")).results
            }
        }
        val nowPlayingDeferred = async {
            softShelf("nowPlaying", emptyList()) {
                TMDb3.MovieLists.nowPlaying(language = language, page = 1, region = watchRegion).results
            }
        }
        val upcomingDeferred = async {
            softShelf("upcoming", emptyList()) {
                TMDb3.MovieLists.upcoming(language = language, page = 1, region = watchRegion).results
            }
        }
        val airingTodayDeferred = async {
            softShelf("airingToday", emptyList()) {
                TMDb3.TvSeriesLists.airingToday(language = language, page = 1).results
            }
        }
        val onTheAirDeferred = async {
            softShelf("onTheAir", emptyList()) {
                TMDb3.TvSeriesLists.onTheAir(language = language, page = 1).results
            }
        }

        fun popularityOf(item: TMDb3.MultiItem): Float = when (item) {
            is TMDb3.Movie -> item.popularity
            is TMDb3.Person -> item.popularity
            is TMDb3.Tv -> item.popularity
        }

        val trending = trendingDeferred.await()
        if (trending.isNotEmpty()) {
            categories.add(
                Category(
                    name = Category.FEATURED,
                    list = trending.safeSubList(0, 5).mapNotNull(mapMulti)
                )
            )
            categories.add(
                Category(
                    name = getTranslation("Trending"),
                    list = trending.safeSubList(5, trending.size).mapNotNull(mapMulti)
                )
            )
        }

        fun addShelf(name: String, list: List<AppAdapter.Item>) {
            if (list.isNotEmpty()) {
                categories.add(Category(name = name, list = list))
            }
        }

        addShelf(getTranslation("Now Playing"), nowPlayingDeferred.await().map(mapMovie))
        addShelf(getTranslation("Airing Today"), airingTodayDeferred.await().map(mapTv))
        addShelf(getTranslation("Popular Movies"), popularMoviesDeferred.await().mapNotNull(mapMulti))
        addShelf(getTranslation("Popular TV Shows"), popularTvShowsDeferred.await().mapNotNull(mapMulti))
        addShelf(getTranslation("Top Rated Movies"), topRatedMoviesDeferred.await().map(mapMovie))
        addShelf(getTranslation("Top Rated TV Shows"), topRatedTvDeferred.await().map(mapTv))
        addShelf(getTranslation("On The Air"), onTheAirDeferred.await().map(mapTv))
        addShelf(getTranslation("Upcoming"), upcomingDeferred.await().map(mapMovie))
        addShelf(
            getTranslation("Popular Anime"),
            popularAnimeDeferred.await().sortedByDescending(::popularityOf).mapNotNull(mapMulti),
        )
        addShelf(
            getTranslation("Popular on Netflix"),
            netflixDeferred.await().sortedByDescending(::popularityOf).mapNotNull(mapMulti),
        )
        addShelf(
            getTranslation("Popular on Amazon"),
            amazonDeferred.await().sortedByDescending(::popularityOf).mapNotNull(mapMulti),
        )
        addShelf(
            getTranslation("Popular on Disney+"),
            disneyDeferred.await().sortedByDescending(::popularityOf).mapNotNull(mapMulti),
        )
        addShelf(
            getTranslation("Popular on Hulu"),
            huluDeferred.await().sortedByDescending(::popularityOf).mapNotNull(mapMulti),
        )
        addShelf(
            getTranslation("Popular on Apple TV+"),
            appleDeferred.await().sortedByDescending(::popularityOf).mapNotNull(mapMulti),
        )
        addShelf(getTranslation("Popular on HBO"), hboDeferred.await().mapNotNull(mapMulti))

        if (categories.isEmpty()) {
            throw Exception(
                "TMDb returned no catalog shelves. Check your API key and connection, then retry.",
            )
        }
        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) {
            val genres = listOf(
                TMDb3.Genres.movieList(language = language),
                TMDb3.Genres.tvList(language = language),
            ).flatMap { it.genres }
                .distinctBy { it.id }
                .sortedBy { it.name }
                .map {
                    Genre(
                        id = it.id.toString(),
                        name = it.name,
                    )
                }

            return genres
        }

        val results = TMDb3.Search.multi(query, page = page, language = language).results.mapNotNull { multi ->
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
        }

        return results
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val pageResult = if (UserPreferences.catalogSortMode == CatalogSortMode.LAST_RELEASE) {
            TMDb3.Discover.movie(
                page = page,
                language = language,
                sortBy = TMDb3.Params.SortBy.Movie.PRIMARY_RELEASE_DATE_DESC,
            )
        } else {
            TMDb3.MovieLists.popular(page = page, language = language)
        }
        val movies = pageResult.results.map { movie ->
            Movie(
                id = movie.id.toString(),
                title = movie.title,
                overview = movie.overview,
                released = movie.releaseDate,
                rating = movie.voteAverage.toDouble(),
                poster = movie.posterPath?.w500,
                banner = movie.backdropPath?.original,
                tmdbId = movie.id.toString(),
            )
        }

        return movies
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val pageResult = if (UserPreferences.catalogSortMode == CatalogSortMode.LAST_RELEASE) {
            TMDb3.Discover.tv(
                page = page,
                language = language,
                sortBy = TMDb3.Params.SortBy.Tv.FIRST_AIR_DATE_DESC,
            )
        } else {
            TMDb3.TvSeriesLists.popular(page = page, language = language)
        }
        val tvShows = pageResult.results.map { tv ->
            TvShow(
                id = tv.id.toString(),
                title = tv.name,
                overview = tv.overview,
                released = tv.firstAirDate,
                rating = tv.voteAverage.toDouble(),
                poster = tv.posterPath?.w500,
                banner = tv.backdropPath?.original,
                tmdbId = tv.id.toString(),
            )
        }

        return tvShows
    }

    override suspend fun getMovie(id: String): Movie {
        val movie = TMDb3.Movies.details(
            movieId = id.toInt(),
            appendToResponse = listOf(
                TMDb3.Params.AppendToResponse.Movie.CREDITS,
                TMDb3.Params.AppendToResponse.Movie.RECOMMENDATIONS,
                TMDb3.Params.AppendToResponse.Movie.VIDEOS,
                TMDb3.Params.AppendToResponse.Movie.EXTERNAL_IDS,
            ),
            language = language
        ).let { movie ->
            Movie(
                id = movie.id.toString(),
                title = movie.title,
                overview = movie.overview,
                released = movie.releaseDate,
                runtime = movie.runtime,
                trailer = movie.videos?.results
                    ?.sortedBy { it.publishedAt ?: "" }
                    ?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = movie.voteAverage.toDouble(),
                poster = movie.posterPath?.original,
                banner = movie.backdropPath?.original,
                imdbId = movie.externalIds?.imdbId,
                tmdbId = movie.id.toString(),

                genres = movie.genres.map { genre ->
                    Genre(
                        genre.id.toString(),
                        genre.name,
                    )
                },
                cast = movie.credits?.cast?.map { cast ->
                    People(
                        id = cast.id.toString(),
                        name = cast.name,
                        image = cast.profilePath?.w500,
                    ).also { it.character = cast.character.takeIf { role -> role.isNotBlank() } }
                } ?: listOf(),
                recommendations = movie.recommendations?.results?.mapNotNull { multi ->
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
                } ?: listOf(),
            )
        }

        return movie
    }

    override suspend fun getTvShow(id: String): TvShow {
        val tvShow = TMDb3.TvSeries.details(
            seriesId = id.toInt(),
            appendToResponse = listOf(
                TMDb3.Params.AppendToResponse.Tv.CREDITS,
                TMDb3.Params.AppendToResponse.Tv.RECOMMENDATIONS,
                TMDb3.Params.AppendToResponse.Tv.VIDEOS,
                TMDb3.Params.AppendToResponse.Tv.EXTERNAL_IDS,
            ),
            language = language
        ).let { tv ->
            TvShow(
                id = tv.id.toString(),
                title = tv.name,
                overview = tv.overview,
                released = tv.firstAirDate,
                trailer = tv.videos?.results
                    ?.sortedBy { it.publishedAt ?: "" }
                    ?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = tv.voteAverage.toDouble(),
                poster = tv.posterPath?.original,
                banner = tv.backdropPath?.original,
                imdbId = tv.externalIds?.imdbId,
                tmdbId = tv.id.toString(),

                seasons = tv.seasons.map { season ->
                    Season(
                        id = "${tv.id}-${season.seasonNumber}",
                        number = season.seasonNumber,
                        title = season.name,
                        poster = season.posterPath?.w500,
                    )
                },
                genres = tv.genres.map { genre ->
                    Genre(
                        genre.id.toString(),
                        genre.name,
                    )
                },
                cast = tv.credits?.cast?.map { cast ->
                    People(
                        id = cast.id.toString(),
                        name = cast.name,
                        image = cast.profilePath?.w500,
                    ).also { it.character = cast.character.takeIf { role -> role.isNotBlank() } }
                } ?: listOf(),
                recommendations = tv.recommendations?.results?.mapNotNull { multi ->
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
                } ?: listOf(),
            )
        }

        return tvShow
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val seasonNumber = seasonId.substringAfterLast('-')
        val tvShowId = seasonId.substringBeforeLast('-')

        val episodes = TMDb3.TvSeasons.details(
            seriesId = tvShowId.toInt(),
            seasonNumber = seasonNumber.toInt(),
            language = language
        ).episodes?.map {
            Episode(
                id = it.id.toString(),
                number = it.episodeNumber,
                title = it.name ?: "",
                released = it.airDate,
                poster = it.stillPath?.w500,
            )
        } ?: listOf()

        return episodes
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        fun <T> List<T>.mix(other: List<T>): List<T> {
            return sequence {
                val first = iterator()
                val second = other.iterator()
                while (first.hasNext() && second.hasNext()) {
                    yield(first.next())
                    yield(second.next())
                }

                yieldAll(first)
                yieldAll(second)
            }.toList()
        }

        val genre = Genre(
            id = id,
            name = "",

            shows = TMDb3.Discover.movie(
                page = page,
                withGenres = TMDb3.Params.WithBuilder(id),
                language = language
            ).results.map { movie ->
                Movie(
                    id = movie.id.toString(),
                    title = movie.title,
                    overview = movie.overview,
                    released = movie.releaseDate,
                    rating = movie.voteAverage.toDouble(),
                    poster = movie.posterPath?.w500,
                    banner = movie.backdropPath?.original,
                )
            }.mix(TMDb3.Discover.tv(
                page = page,
                withGenres = TMDb3.Params.WithBuilder(id),
                language = language
            ).results.map { tv ->
                TvShow(
                    id = tv.id.toString(),
                    title = tv.name,
                    overview = tv.overview,
                    released = tv.firstAirDate,
                    rating = tv.voteAverage.toDouble(),
                    poster = tv.posterPath?.w500,
                    banner = tv.backdropPath?.original,
                )
            })
        )

        return genre
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val people = TMDb3.People.details(
            personId = id.toInt(),
            appendToResponse = listOfNotNull(
                if (page > 1) null else TMDb3.Params.AppendToResponse.Person.COMBINED_CREDITS,
            ),
            language = language
        ).let { person ->
            People(
                id = person.id.toString(),
                name = person.name,
                image = person.profilePath?.w500,
                biography = person.biography,
                placeOfBirth = person.placeOfBirth,
                birthday = person.birthday,
                deathday = person.deathday,

                filmography = person.combinedCredits?.let { credits ->
                    val seen = LinkedHashSet<String>()
                    (credits.cast + credits.crew).mapNotNull { multi ->
                        when (multi) {
                            is TMDb3.Movie -> {
                                val key = "m:${multi.id}"
                                if (!seen.add(key) || multi.title.isBlank()) return@mapNotNull null
                                Movie(
                                    id = multi.id.toString(),
                                    title = multi.title,
                                    overview = multi.overview,
                                    released = multi.releaseDate,
                                    rating = multi.voteAverage.toDouble(),
                                    poster = multi.posterPath?.w500,
                                    banner = multi.backdropPath?.original,
                                    tmdbId = multi.id.toString(),
                                ) to multi.popularity
                            }

                            is TMDb3.Tv -> {
                                val key = "t:${multi.id}"
                                if (!seen.add(key) || multi.name.isBlank()) return@mapNotNull null
                                TvShow(
                                    id = multi.id.toString(),
                                    title = multi.name,
                                    overview = multi.overview,
                                    released = multi.firstAirDate,
                                    rating = multi.voteAverage.toDouble(),
                                    poster = multi.posterPath?.w500,
                                    banner = multi.backdropPath?.original,
                                    tmdbId = multi.id.toString(),
                                ) to (multi.popularity ?: 0f)
                            }

                            else -> null
                        }
                    }
                        .sortedByDescending { it.second }
                        .map { it.first }
                } ?: listOf(),
                knownForDepartment = person.knownForDepartment?.value,
            )
        }

        return people
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val servers = mutableListOf<Video.Server>()
        val lang = language.lowercase().substringBefore("-")

        Log.d("TmdbProvider", "getServers: lang=$language, simplifiedLang=$lang")

        when (lang) {
            "it" -> {
                // Se la lingua è italiano, includiamo solo i server noti per l'italiano.
                servers.add(VixSrcExtractor().server(videoType))
            }
            "de" -> {
                val targetTitle = when (videoType) {
                    is Video.Type.Movie -> videoType.title
                    is Video.Type.Episode -> videoType.tvShow.title
                }
                val originalTitle = runCatching { fetchGermanOriginalTitle(videoType) }.getOrNull()
                val titleQueries = linkedSetOf(targetTitle).apply {
                    if (!originalTitle.isNullOrBlank()) add(originalTitle)
                }

                // Prefer real German hosters first (SerienStream etc.) — extractors often
                // return optimistic dead servers that waste failover into "not available".
                val nativeProviders: List<Provider> = buildList {
                    if (videoType is Video.Type.Episode) {
                        add(SerienStreamProvider)
                        add(AniWorldProvider)
                    }
                    add(VavooVodProvider.DE)
                    add(KinoGerProvider)
                    add(HDFilmeProvider)
                    add(MEGAKinoProvider)
                    add(FilmPalastProvider)
                    if (videoType is Video.Type.Movie) {
                        add(KellerKinoProvider)
                        add(FilmoProvider)
                    }
                }
                val nativeServers = resolveGermanNativeServers(
                    providers = nativeProviders,
                    titleQueries = titleQueries.toList(),
                    videoType = videoType,
                )
                servers.addAll(nativeServers)

                // Direct extractors as fallback when natives miss
                runCatching { servers.addAll(MoflixExtractor().servers(videoType)) }
                if (videoType is Video.Type.Movie) {
                    runCatching { servers.add(EinschaltenExtractor().server(videoType)) }
                }
                runCatching {
                    VideasyExtractor().server(videoType, language)?.let { servers.add(it) }
                }
            }
            "fr" -> {
                // Solo server francesi
                servers.addAll(FrembedExtractor(UserPreferences.getProviderCache(FrembedProvider, UserPreferences.PROVIDER_URL)).servers(videoType))
                servers.addAll(AfterDarkExtractor(UserPreferences.getProviderCache(AfterDarkProvider, UserPreferences.PROVIDER_URL)).servers(videoType))
            }
            "es" -> {
                // TMDB Spagnolo: Utilizza ESCLUSIVAMENTE server certificati con audio spagnolo ([LAT] o [CAST])
                
                val targetTitle = when (videoType) {
                    is Video.Type.Movie -> videoType.title
                    is Video.Type.Episode -> videoType.tvShow.title
                }
                
                Log.i("BetterStreamflix", "[SEARCH START] -> Target: $targetTitle (${if (videoType is Video.Type.Movie) "Movie" else "TV Show"})")

                // Funzione di matching rigorosa per i titoli e tipo
                fun isMatch(item: AppAdapter.Item, target: String): Boolean {
                    val isCorrectType = if (videoType is Video.Type.Movie) item is Movie else item is TvShow
                    if (!isCorrectType) return false

                    val itemTitle = if (item is Movie) item.title else (item as TvShow).title
                    val nItem = itemTitle.lowercase().replace(Regex("[^a-z0-9]"), "")
                    val nTarget = target.lowercase().replace(Regex("[^a-z0-9]"), "")
                    
                    // Match esatto (normalizzato) ha la priorità
                    if (nItem == nTarget) return true
                    
                    // Match parziale se contenuto e differenza lunghezza minima
                    if (nItem.contains(nTarget) || nTarget.contains(nItem)) {
                        val diff = Math.abs(nItem.length - nTarget.length)
                        if (diff <= 5) return true
                    }
                    
                    // Match per parole (almeno una deve corrispondere esattamente se il target è corto, o tutte se lungo)
                    val cleanWords: (String) -> Set<String> = { s ->
                        s.lowercase()
                            .replace(Regex("[^a-z0-9 ]"), " ")
                            .split(Regex("\\s+"))
                            .filter { it.length > 2 }
                            .toSet()
                    }
                    val nItemWords = cleanWords(itemTitle)
                    val nTargetWords = cleanWords(target)
                    
                    if (nItemWords.isEmpty() || nTargetWords.isEmpty()) return false
                    
                    // Se il target ha solo una parola importante, deve esserci
                    if (nTargetWords.size == 1) return nItemWords.contains(nTargetWords.first())
                    
                    // Altrimenti tutte le parole del target devono essere presenti nell'item
                    return nItemWords.containsAll(nTargetWords) || nTargetWords.containsAll(nItemWords)
                }

                coroutineScope {
                    val providers = listOf(CuevanaEuProvider, PelisplustoProvider, SoloLatinoProvider, CineCalidadProvider, PoseidonHD2Provider)
                    val deferred = providers.map { provider ->
                        async {
                            try {
                                val searchResults = provider.search(targetTitle, 1)
                                val bestMatch = searchResults.firstOrNull { isMatch(it, targetTitle) }
                                val id = if (bestMatch is Movie) bestMatch.id else (bestMatch as? TvShow)?.id
                                
                                if (id != null) {
                                    val matchTitle = if (bestMatch is Movie) bestMatch.title else (bestMatch as? TvShow)?.title
                                    Log.i("BetterStreamflix", "[MATCH FOUND] -> Provider: ${provider.name}, Matched: '$matchTitle', ID: $id")
                                    
                                    val allServers = provider.getServers(id, videoType)
                                    val filtered = allServers.filter { s ->
                                        val n = s.name.uppercase()
                                        n.contains("[LAT]") || n.contains("[CAST]") || n.contains("[CAS]") || n.contains("[ES]") ||
                                        n.contains("(LAT)") || n.contains("(ESP)") || n.contains("LATINO") || n.contains("CASTELLANO")
                                    }
                                    Log.i("BetterStreamflix", "[SERVERS OK] -> ${provider.name}: ${filtered.size}/${allServers.size} servers kept")
                                    filtered
                                } else {
                                    Log.d("BetterStreamflix", "[NO MATCH] -> ${provider.name} did not find a valid match for '$targetTitle'")
                                    emptyList()
                                }
                            } catch (e: Exception) { 
                                Log.e("BetterStreamflix", "[PROVIDER ERROR] -> ${provider.name}: ${e.message}")
                                emptyList() 
                            }
                        }
                    }
                    servers.addAll(deferred.awaitAll().flatten())
                }
            }
            else -> {
                // Per inglese (en) o altre lingue non specifiche, usiamo i server globali
                servers.addAll(listOf(
                    VixSrcExtractor().server(videoType),
                    TwoEmbedExtractor().server(videoType),
                    VidsrcNetExtractor().server(videoType),
                    VidLinkExtractor().server(videoType),
                    VidsrcRuExtractor().server(videoType),
                    VidflixExtractor().server(videoType),
                ))

                if (videoType is Video.Type.Movie) {
                    servers.add(2, MoviesapiExtractor().server(videoType))
                }

                servers.addAll(VidrockExtractor().servers(videoType))
                servers.addAll(VidzeeExtractor().servers(videoType))
                servers.addAll(PrimeSrcExtractor().servers(videoType))

                if (language == "en") {
                    servers.addAll(1, VideasyExtractor().servers(videoType, language))
                }
            }
        }

        // Prefer language-matched audio servers first (Spanish LAT/CAST, French VF)
        val finalServers = when {
            language.startsWith("es") -> servers.sortedByDescending { server ->
                val n = server.name.uppercase()
                when {
                    // Filemoon e tag audio spagnoli hanno la massima priorità
                    n.contains("FILEMOON") -> 110
                    n.contains("[CAS]") || n.contains("[LAT]") || n.contains("[ES]") || n.contains("SPAIN") || n.contains("[CAST]") ||
                    n.contains("LATINO") || n.contains("SPANISH") || n.contains("CASTELLANO") || n.contains("(LAT)") || n.contains("(ESP)") -> 100
                    
                    // Altri aggregatori multi-lingua
                    n.contains("VIDSRC") || n.contains("VIDLINK") -> 80
                    
                    // Sottotitoli o inglese
                    n.contains("[EN]") || n.contains("[SUB]") || n.contains("(EN)") || n.contains("(SUB)") -> 50
                    
                    else -> 0
                }
            }
            language.startsWith("fr") -> servers.sortedByDescending { server ->
                ProviderAudioLanguage.frenchServerPriority(server.name)
            }
            else -> servers
        }

        Log.i("BetterStreamflix", "[SERVERS LIST] -> Found ${finalServers.size} servers: ${finalServers.joinToString { it.name }}")
        return finalServers.distinctBy { it.id }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val url = server.src.ifEmpty { server.id }
        Log.i("BetterStreamflix", "[SERVER] -> Using: ${server.name} (URL: $url)")

        if (server.id.startsWith(TMDB_DE_PREFIX)) {
            val routed = routeGermanNativeVideo(server)
            if (routed != null) return routed
        }

        val video = when {
            server.video != null -> server.video!!
            else -> Extractor.extract(url, server)
        }

        // LOGICA SOTTOTITOLI FORZATI: Se siamo in spagnolo, attiviamo solo i forced di default
        if (language.startsWith("es")) {
            var forcedFound = false
            video.subtitles.forEach { sub ->
                val label = sub.label.lowercase()
                val isSpanish = label.contains("spanish") || label.contains("español") || 
                                label.contains("espanol") || label.contains("castellano") || 
                                label.contains(" lat ")
                val isForced = label.contains("forced") || label.contains("forzati") || label.contains("forzato")

                if (isSpanish && isForced) {
                    sub.default = true
                    forcedFound = true
                    Log.i("BetterStreamflix", "[SUBTITLE] -> TMDb (es): Selected FORCED subtitle: ${sub.label}")
                } else {
                    sub.default = false
                }
            }
            
            if (!forcedFound) {
                video.subtitles.forEach { it.default = false }
                Log.i("BetterStreamflix", "[SUBTITLE] -> TMDb (es): No forced subs found, keeping them OFF")
            }
        }
        
        Log.i("BetterStreamflix", "[VIDEO] -> Final source: ${video.source}")
        return video
    }

    private suspend fun fetchGermanOriginalTitle(videoType: Video.Type): String? {
        return when (videoType) {
            is Video.Type.Movie -> {
                val id = videoType.id.toIntOrNull() ?: return null
                TMDb3.Movies.details(movieId = id, language = "en").originalTitle
                    ?.takeIf { it.isNotBlank() && !it.equals(videoType.title, ignoreCase = true) }
            }
            is Video.Type.Episode -> {
                val id = videoType.tvShow.id.toIntOrNull() ?: return null
                TMDb3.TvSeries.details(seriesId = id, language = "en").originalName
                    ?.takeIf { it.isNotBlank() && !it.equals(videoType.tvShow.title, ignoreCase = true) }
            }
        }
    }

    private suspend fun resolveGermanNativeServers(
        providers: List<Provider>,
        titleQueries: List<String>,
        videoType: Video.Type,
    ): List<Video.Server> = coroutineScope {
        providers.map { provider ->
            async {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val timeoutMs = if (provider == SerienStreamProvider) 25_000L else 15_000L
                        kotlinx.coroutines.withTimeout(timeoutMs) {
                            var bestItem: AppAdapter.Item? = null
                            var bestScore = 0
                            for (query in titleQueries.filter { it.isNotBlank() }) {
                                val results = runCatching { provider.search(query, 1) }
                                    .getOrDefault(emptyList())
                                for (item in results) {
                                    val candidateTitle = when {
                                        videoType is Video.Type.Movie && item is Movie -> item.title
                                        videoType is Video.Type.Episode && item is TvShow -> item.title
                                        else -> continue
                                    }
                                    val score = titleQueries.maxOf { germanTitleScore(candidateTitle, it) }
                                    if (score > bestScore) {
                                        bestScore = score
                                        bestItem = item
                                    }
                                }
                                if (bestScore >= 90) break
                            }
                            if (bestScore < 45 || bestItem == null) {
                                Log.d(
                                    "TmdbProvider",
                                    "DE native miss ${provider.name}: bestScore=$bestScore queries=$titleQueries",
                                )
                                return@withTimeout emptyList()
                            }

                            when (videoType) {
                                is Video.Type.Movie -> {
                                    val movie = bestItem as Movie
                                    provider.getServers(movie.id, videoType).map { server ->
                                        wrapGermanNativeServer(provider, server)
                                    }
                                }
                                is Video.Type.Episode -> {
                                    val show = bestItem as TvShow
                                    val detailed = runCatching { provider.getTvShow(show.id) }.getOrDefault(show)
                                    val season = detailed.seasons.firstOrNull {
                                        it.number == videoType.season.number
                                    } ?: detailed.seasons.firstOrNull()
                                        ?: return@withTimeout emptyList()
                                    val episodes = provider.getEpisodesBySeason(season.id)
                                    val episode = episodes.firstOrNull { it.number == videoType.number }
                                        ?: return@withTimeout emptyList()
                                    val episodeType = Video.Type.Episode(
                                        id = episode.id,
                                        number = episode.number,
                                        title = episode.title,
                                        poster = episode.poster,
                                        overview = episode.overview,
                                        tvShow = Video.Type.Episode.TvShow(
                                            id = detailed.id,
                                            title = detailed.title,
                                            poster = detailed.poster,
                                            banner = detailed.banner,
                                            releaseDate = null,
                                            imdbId = detailed.imdbId ?: videoType.tvShow.imdbId,
                                        ),
                                        season = Video.Type.Episode.Season(
                                            number = season.number,
                                            title = season.title,
                                        ),
                                    )
                                    provider.getServers(episode.id, episodeType).map { server ->
                                        wrapGermanNativeServer(provider, server)
                                    }
                                }
                            }
                        }
                    }
                }.onFailure {
                    Log.e("TmdbProvider", "DE native provider ${provider.name} failed: ${it.message}")
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }

    private fun wrapGermanNativeServer(provider: Provider, server: Video.Server): Video.Server {
        val key = germanProviderKey(provider)
        // Keep original SerienStream play URL in src when present so CF host detection works;
        // also embed episode path in id for bypass page construction under TMDb.
        return Video.Server(
            id = "$TMDB_DE_PREFIX$key:${server.id}",
            name = "${provider.name} • ${server.name}",
            src = server.src.ifBlank { server.id },
        )
    }

    private suspend fun routeGermanNativeVideo(server: Video.Server): Video? {
        val remainder = server.id.removePrefix(TMDB_DE_PREFIX)
        val providerKey = remainder.substringBefore(':')
        val originalId = remainder.substringAfter(':', missingDelimiterValue = "")
        if (providerKey.isBlank() || originalId.isBlank()) return null
        val provider = germanProviderByKey(providerKey) ?: return null
        val original = Video.Server(
            id = originalId,
            name = server.name.substringAfter(" • ").ifBlank { server.name },
            src = server.src.ifBlank { originalId },
        )
        return runCatching { provider.getVideo(original) }.getOrNull()
    }

    private fun germanProviderKey(provider: Provider): String = when (provider) {
        KinoGerProvider -> "kinoger"
        HDFilmeProvider -> "hdfilme"
        MEGAKinoProvider -> "megakino"
        FilmPalastProvider -> "filmpalast"
        FilmoProvider -> "filmo"
        SerienStreamProvider -> "serienstream"
        AniWorldProvider -> "aniworld"
        KellerKinoProvider -> "kellerkino"
        VavooVodProvider.DE -> "vavoo"
        else -> provider.name.lowercase().replace(Regex("[^a-z0-9]+"), "")
    }

    private fun germanProviderByKey(key: String): Provider? = when (key) {
        "kinoger" -> KinoGerProvider
        "hdfilme" -> HDFilmeProvider
        "megakino" -> MEGAKinoProvider
        "filmpalast" -> FilmPalastProvider
        "filmo" -> FilmoProvider
        "serienstream" -> SerienStreamProvider
        "aniworld" -> AniWorldProvider
        "kellerkino" -> KellerKinoProvider
        "vavoo" -> VavooVodProvider.DE
        else -> null
    }

    private fun germanTitleScore(candidate: String, target: String): Int {
        fun normalize(value: String): String =
            value.lowercase()
                .replace("ä", "ae")
                .replace("ö", "oe")
                .replace("ü", "ue")
                .replace("ß", "ss")
                .replace(Regex("[^a-z0-9]"), "")

        fun words(value: String): Set<String> =
            value.lowercase()
                .replace("ä", "ae")
                .replace("ö", "oe")
                .replace("ü", "ue")
                .replace("ß", "ss")
                .replace(Regex("[^a-z0-9 ]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length > 2 }
                .toSet()

        val a = normalize(candidate)
        val b = normalize(target)
        if (a.isEmpty() || b.isEmpty()) return 0
        if (a == b) return 100
        if (a.startsWith(b) || b.startsWith(a)) return 85
        val diff = kotlin.math.abs(a.length - b.length)
        if ((a.contains(b) || b.contains(a)) && diff <= 8) return 75

        val aw = words(candidate)
        val bw = words(target)
        if (aw.isEmpty() || bw.isEmpty()) return 0
        if (aw == bw) return 95
        if (aw.containsAll(bw) || bw.containsAll(aw)) return 80
        val overlap = aw.intersect(bw).size
        val needed = minOf(aw.size, bw.size)
        if (needed > 0 && overlap * 2 >= needed) return 55
        return 0
    }

    private fun getTranslation(key: String): String {
        return when (language) {
            "it" -> when (key) {
                "Trending" -> "Di tendenza"
                "Now Playing" -> "Ora al cinema"
                "Airing Today" -> "In onda oggi"
                "Popular Movies" -> "Film popolari"
                "Popular TV Shows" -> "Serie TV popolari"
                "Top Rated Movies" -> "Film più votati"
                "Top Rated TV Shows" -> "Serie più votate"
                "On The Air" -> "In onda"
                "Upcoming" -> "Prossimamente"
                "Popular Anime" -> "Anime popolari"
                "Popular on Netflix" -> "Popolari su Netflix"
                "Popular on Amazon" -> "Popolari su Amazon"
                "Popular on Disney+" -> "Popolari su Disney+"
                "Popular on Hulu" -> "Popolari su Hulu"
                "Popular on Apple TV+" -> "Popolari su Apple TV+"
                "Popular on HBO" -> "Popolari su HBO"
                else -> key
            }
            "es" -> when (key) {
                "Trending" -> "Tendencias"
                "Now Playing" -> "En cartelera"
                "Airing Today" -> "Se emite hoy"
                "Popular Movies" -> "Películas populares"
                "Popular TV Shows" -> "Series de TV populares"
                "Top Rated Movies" -> "Películas mejor valoradas"
                "Top Rated TV Shows" -> "Series mejor valoradas"
                "On The Air" -> "En emisión"
                "Upcoming" -> "Próximamente"
                "Popular Anime" -> "Anime populares"
                "Popular on Netflix" -> "Popular en Netflix"
                "Popular on Amazon" -> "Popular en Amazon"
                "Popular on Disney+" -> "Popular en Disney+"
                "Popular on Hulu" -> "Popular en Hulu"
                "Popular on Apple TV+" -> "Popular en Apple TV+"
                "Popular on HBO" -> "Popular en HBO"
                else -> key
            }
            "de" -> when (key) {
                "Trending" -> "Trends"
                "Now Playing" -> "Jetzt im Kino"
                "Airing Today" -> "Heute im TV"
                "Popular Movies" -> "Beliebte Filme"
                "Popular TV Shows" -> "Beliebte Serien"
                "Top Rated Movies" -> "Bestbewertete Filme"
                "Top Rated TV Shows" -> "Bestbewertete Serien"
                "On The Air" -> "Aktuell im TV"
                "Upcoming" -> "Demnächst"
                "Popular Anime" -> "Beliebte Anime"
                "Popular on Netflix" -> "Beliebt bei Netflix"
                "Popular on Amazon" -> "Beliebt bei Amazon"
                "Popular on Disney+" -> "Beliebt bei Disney+"
                "Popular on Hulu" -> "Beliebt bei Hulu"
                "Popular on Apple TV+" -> "Beliebt bei Apple TV+"
                "Popular on HBO" -> "Beliebt bei HBO"
                else -> key
            }
            "fr" -> when (key) {
                "Trending" -> "Tendances"
                "Now Playing" -> "À l'affiche"
                "Airing Today" -> "Diffusé aujourd'hui"
                "Popular Movies" -> "Films populaires"
                "Popular TV Shows" -> "Séries populaires"
                "Top Rated Movies" -> "Films les mieux notés"
                "Top Rated TV Shows" -> "Séries les mieux notées"
                "On The Air" -> "En cours de diffusion"
                "Upcoming" -> "À venir"
                "Popular Anime" -> "Animes populaires"
                "Popular on Netflix" -> "Populaire sur Netflix"
                "Popular on Amazon" -> "Populaire sur Amazon"
                "Popular on Disney+" -> "Populaire sur Disney+"
                "Popular on Hulu" -> "Populaire sur Hulu"
                "Popular on Apple TV+" -> "Populaire sur Apple TV+"
                "Popular on HBO" -> "Populaire sur HBO"
                else -> key
            }
            else -> key
        }
    }
}
