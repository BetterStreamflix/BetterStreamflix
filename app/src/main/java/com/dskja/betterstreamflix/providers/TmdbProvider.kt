package com.dskja.betterstreamflix.providers

import com.dskja.betterstreamflix.BuildConfig
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.extractors.AfterDarkExtractor
import com.dskja.betterstreamflix.extractors.Extractor
import com.dskja.betterstreamflix.extractors.MoflixExtractor
import com.dskja.betterstreamflix.extractors.VidzeeExtractor
import com.dskja.betterstreamflix.extractors.VixSrcExtractor
import com.dskja.betterstreamflix.extractors.EinschaltenExtractor
import com.dskja.betterstreamflix.extractors.FrembedExtractor
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
import com.dskja.betterstreamflix.ui.StickEnergyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class TmdbProvider private constructor(override val language: String) : Provider {
    companion object {
        private const val TMDB_DE_PREFIX = "tmdbde:"

        private val instances =
            java.util.concurrent.ConcurrentHashMap<String, TmdbProvider>()

        /** Soft cooldown after Moflix returns empty / times out (anti-bot / premium). */
        @Volatile
        private var moflixSkipUntilMs: Long = 0L

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
            // Soft-empty used to clear the home circuit — fail loud so HomeViewModel
            // notes a failure instead of noteHomeSuccess on an empty catalog.
            Log.e("TmdbProvider", "TMDB home ClassCast soft-fail: ${e.message}", e)
            throw Exception(
                "TMDb catalog parsing failed (ClassCast). Retry shortly or check API key.",
                e,
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (e.cause is ClassCastException) {
                Log.e("TmdbProvider", "TMDB home ClassCast (cause) soft-fail: ${e.message}", e)
                throw Exception(
                    "TMDb catalog parsing failed (ClassCast). Retry shortly or check API key.",
                    e,
                )
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

    private suspend fun buildHomeCategories(): List<Category> {
        val categories = mutableListOf<Category>()
        try {
            fillHomeCategories(categories)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Outer Home timeout cancels in-flight shelves — keep whatever already landed.
            if (categories.isNotEmpty()) {
                Log.w(
                    "TmdbProvider",
                    "TMDb home cancelled — returning ${categories.size} partial shelves",
                )
                return categories
            }
            throw e
        }
        if (categories.isEmpty()) {
            throw Exception(
                "TMDb returned no catalog shelves. Check your API key and connection, then retry.",
            )
        }
        return categories
    }

    private suspend fun fillHomeCategories(categories: MutableList<Category>) = coroutineScope {
        val watchRegion = if (language == "en") "US" else language.uppercase()
        // SE-002: cap Home shelf fan-out under Stick Energy (serial on sticks).
        val homeShelfGate = Semaphore(
            StickEnergyMode.providerParallelism(default = 4),
        )
        suspend fun <T> gatedShelf(
            label: String,
            fallback: T,
            block: suspend kotlinx.coroutines.CoroutineScope.() -> T,
        ): T = homeShelfGate.withPermit {
            softShelf(label, fallback, block = block)
        }

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
            gatedShelf("trending", emptyList()) {
                // compactResults drops Gson nulls from incomplete media_type rows.
                TMDb3.Trending.all(TMDb3.Params.TimeWindow.DAY, page = 1, language = language)
                    .results
                    .filterNotNull()
            }
        }

        val popularMoviesDeferred = async {
            gatedShelf("popularMovies", emptyList()) {
                TMDb3.MovieLists.popular(page = 1, language = language).results
            }
        }

        val popularTvShowsDeferred = async {
            gatedShelf("popularTv", emptyList()) {
                TMDb3.TvSeriesLists.popular(page = 1, language = language).results
            }
        }

        val popularAnimeDeferred = async {
            gatedShelf("anime", emptyList()) {
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
        ): List<TMDb3.MultiItem> = gatedShelf(label, emptyList()) {
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
            gatedShelf("hbo", emptyList()) {
                TMDb3.Discover.tv(
                    language = language,
                    withNetworks = TMDb3.Params.WithBuilder(TMDb3.Network.NetworkId.HBO),
                    page = 1,
                ).results
            }
        }

        val topRatedMoviesDeferred = async {
            gatedShelf("topRatedMovies", emptyList()) {
                TMDb3.MovieLists.topRated(mapOf("language" to language, "page" to "1")).results
            }
        }
        val topRatedTvDeferred = async {
            gatedShelf("topRatedTv", emptyList()) {
                TMDb3.TvSeriesLists.topRated(mapOf("language" to language, "page" to "1")).results
            }
        }
        val nowPlayingDeferred = async {
            gatedShelf("nowPlaying", emptyList()) {
                TMDb3.MovieLists.nowPlaying(language = language, page = 1, region = watchRegion).results
            }
        }
        val upcomingDeferred = async {
            gatedShelf("upcoming", emptyList()) {
                TMDb3.MovieLists.upcoming(language = language, page = 1, region = watchRegion).results
            }
        }
        val airingTodayDeferred = async {
            gatedShelf("airingToday", emptyList()) {
                TMDb3.TvSeriesLists.airingToday(language = language, page = 1).results
            }
        }
        val onTheAirDeferred = async {
            gatedShelf("onTheAir", emptyList()) {
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

                is TMDb3.Person -> People(
                    id = multi.id.toString(),
                    name = multi.name,
                    image = multi.profilePath?.w500 ?: multi.profilePath?.original,
                    knownForDepartment = multi.knownForDepartment?.value,
                ).also { person ->
                    // Reuse character subtitle slot for department (Director / Acting / …).
                    person.character = multi.knownForDepartment?.value
                }

                else -> null
            }
        }

        // People / directors first so name searches surface cast before loose title matches.
        return results.sortedBy { item ->
            when (item) {
                is People -> when (item.knownForDepartment?.lowercase()) {
                    "directing" -> 0
                    "acting", "actors" -> 1
                    else -> 2
                }
                else -> 3
            }
        }
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
                overview = it.overview?.takeIf { text -> text.isNotBlank() },
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
                // Italian TMDb: discovery extractors + StreamingCommunity (Vixcloud) + VixSrc.
                servers.addAll(VidzeeExtractor().servers(videoType))
                servers.addAll(PrimeSrcExtractor().servers(videoType))
                runCatching {
                    val title = when (videoType) {
                        is Video.Type.Movie -> videoType.title
                        is Video.Type.Episode -> videoType.tvShow.title
                    }
                    val sc = StreamingCommunityProvider("it")
                    val match = sc.search(title, 1).firstOrNull()
                    val scId = when (match) {
                        is Movie -> match.id
                        is TvShow -> match.id
                        else -> null
                    }
                    if (scId != null) {
                        servers.addAll(sc.getServers(scId, videoType))
                    }
                }.onFailure {
                    Log.w("TmdbProvider", "StreamingCommunity IT fan-out failed: ${it.message}")
                }
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
                // KellerKino is intentionally omitted from TMDb DE aggregation: CF captcha
                // via TMDb yields no streams and times out searches (upstream cf74e3a).
                // Standalone KellerKino provider remains available in the provider picker.
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
                        add(FilmoProvider)
                    }
                }
                /*
                 * Moflix can spend a long time in its anti-bot/session path.
                 * Run it in parallel with native DE providers and soft-timeout
                 * so it never blocks the whole TMDb-DE server list (upstream cf74e3a).
                 * Stick Energy Mode serializes Moflix after natives to cut peak RAM.
                 */
                val discoveredGermanServers = coroutineScope {
                    suspend fun discoverMoflix(): List<Video.Server> {
                        // Skip Moflix when MStream domain recently looked unhealthy —
                        // empty/premium-locked discovery only burns the soft-timeout.
                        if (moflixSkipUntilMs > System.currentTimeMillis()) {
                            Log.d("TmdbProvider", "Moflix skipped (domain cooldown)")
                            return emptyList()
                        }
                        val discovered = withTimeoutOrNull(20_000L) {
                            MoflixExtractor().servers(videoType)
                        }
                        return when {
                            discovered == null -> {
                                Log.w("TmdbProvider", "Moflix discovery timed out")
                                emptyList()
                            }
                            discovered.isEmpty() -> {
                                Log.w("TmdbProvider", "Moflix discovery empty")
                                moflixSkipUntilMs = System.currentTimeMillis() + 10 * 60_000L
                                emptyList()
                            }
                            else -> discovered
                        }
                    }
                    if (StickEnergyMode.isEnabled()) {
                        val native = withContext(Dispatchers.IO) {
                            resolveGermanNativeServers(
                                providers = nativeProviders,
                                titleQueries = titleQueries.toList(),
                                videoType = videoType,
                            )
                        }
                        val moflix = withContext(Dispatchers.IO) { discoverMoflix() }
                        native + moflix
                    } else {
                        val moflixTask = async(Dispatchers.IO) { discoverMoflix() }
                        val nativeTask = async(Dispatchers.IO) {
                            resolveGermanNativeServers(
                                providers = nativeProviders,
                                titleQueries = titleQueries.toList(),
                                videoType = videoType,
                            )
                        }
                        moflixTask.await() + nativeTask.await()
                    }
                }
                servers.addAll(discoveredGermanServers)

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

                val targetYear = when (videoType) {
                    is Video.Type.Movie -> videoType.releaseDate.take(4).takeIf { it.length == 4 }
                    is Video.Type.Episode -> videoType.tvShow.releaseDate?.take(4)?.takeIf { it.length == 4 }
                }

                // Tight title match: prefer equals/startsWith; contains only when both sides > 5.
                fun isMatch(item: AppAdapter.Item, target: String): Boolean {
                    val isCorrectType = if (videoType is Video.Type.Movie) item is Movie else item is TvShow
                    if (!isCorrectType) return false

                    val itemTitle = if (item is Movie) item.title else (item as TvShow).title
                    val nItem = itemTitle.lowercase().replace(Regex("[^a-z0-9]"), "")
                    val nTarget = target.lowercase().replace(Regex("[^a-z0-9]"), "")
                    if (nItem.isEmpty() || nTarget.isEmpty()) return false

                    if (nItem == nTarget || nItem.startsWith(nTarget) || nTarget.startsWith(nItem)) {
                        // ok so far
                    } else if (nItem.length > 5 && nTarget.length > 5 &&
                        (nItem.contains(nTarget) || nTarget.contains(nItem))
                    ) {
                        val diff = kotlin.math.abs(nItem.length - nTarget.length)
                        if (diff > 5) return false
                    } else {
                        return false
                    }

                    if (targetYear != null) {
                        val itemYear = when (item) {
                            is Movie -> item.released?.get(java.util.Calendar.YEAR)?.toString()
                            is TvShow -> item.released?.get(java.util.Calendar.YEAR)?.toString()
                            else -> null
                        }
                        if (itemYear != null && itemYear != targetYear) return false
                    }
                    return true
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
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // Soft-empty per provider, but surface timeouts clearly in logs.
                                Log.e("BetterStreamflix", "[PROVIDER ERROR] -> ${provider.name}: ${e.message}")
                                if (e is java.net.SocketTimeoutException ||
                                    e is java.util.concurrent.TimeoutException ||
                                    e.message?.contains("timeout", ignoreCase = true) == true
                                ) {
                                    Log.w("BetterStreamflix", "[PROVIDER TIMEOUT] -> ${provider.name}")
                                }
                                emptyList()
                            }
                        }
                    }
                    servers.addAll(deferred.awaitAll().flatten())
                }
            }
            else -> {
                // Discovery extractors only — no optimistic VidLink/TwoEmbed/Moviesapi stubs
                // (those burned VIDEO_RESOLVE_TIMEOUT on dead hosts before live ones).
                servers.addAll(VidzeeExtractor().servers(videoType))
                servers.addAll(PrimeSrcExtractor().servers(videoType))
                servers.addAll(VidrockExtractor().servers(videoType))
                if (language == "en") {
                    servers.addAll(VideasyExtractor().servers(videoType, language))
                }
                // Optional last-resort embed that resolves without WebView.
                servers.add(VixSrcExtractor().server(videoType))
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
        val gate = Semaphore(
            StickEnergyMode.providerParallelism(default = providers.size.coerceAtLeast(1)),
        )
        providers.map { provider ->
            async {
                gate.withPermit {
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
                                        wrapGermanNativeServer(provider, server, contentPath = movie.id)
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
                                        wrapGermanNativeServer(
                                            provider,
                                            server,
                                            contentPath = episode.id,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }.onFailure {
                    Log.e("TmdbProvider", "DE native provider ${provider.name} failed: ${it.message}")
                }.getOrDefault(emptyList())
                }
            }
        }.awaitAll().flatten()
    }

    private fun wrapGermanNativeServer(
        provider: Provider,
        server: Video.Server,
        contentPath: String? = null,
    ): Video.Server {
        val key = germanProviderKey(provider)
        // SerienStream/AniWorld servers use /r? play URLs as id — embed the episode
        // path instead so CF bypass can open /serie/… or /anime/stream/… under TMDb DE.
        // Keep the play URL in src for getVideo / host detection.
        val idSuffix = when (provider) {
            SerienStreamProvider, AniWorldProvider ->
                contentPath?.takeIf {
                    it.isNotBlank() &&
                        !it.startsWith("http", ignoreCase = true) &&
                        !it.contains("/r?", ignoreCase = true)
                } ?: server.id
            else -> server.id
        }
        return Video.Server(
            id = "$TMDB_DE_PREFIX$key:$idSuffix",
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
        // KellerKino intentionally omitted from DE aggregation; legacy cached ids still route.
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
        // Legacy-only: TMDb DE no longer emits kellerkino wraps (CF via TMDb is a dead end).
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
