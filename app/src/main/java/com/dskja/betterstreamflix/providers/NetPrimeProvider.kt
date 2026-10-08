package com.dskja.betterstreamflix.providers

import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.extractors.Extractor
import com.dskja.betterstreamflix.extractors.TwoEmbedExtractor
import com.dskja.betterstreamflix.extractors.VidLinkExtractor
import com.dskja.betterstreamflix.extractors.VideasyExtractor
import com.dskja.betterstreamflix.extractors.VidrockExtractor
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Genre
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.People
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object NetPrimeProvider : Provider {

    override val baseUrl = "https://netprime.gd"
    override val name = "NetPrime"
    override val logo = "$baseUrl/favicon.ico"
    override val language = "en"

    /*
     * NetPrime uses TMDb IDs for both movies and TV shows.
     * Reusing TMDb here keeps catalog/search/detail handling native,
     * while NetPrime remains responsible for the playback source set.
     */
    private val tmdb = TmdbProvider(language)

    override suspend fun getHome(): List<Category> =
        tmdb.getHome().map { category ->
            category.copy(
                list = category.list.map(::markItem)
            )
        }

    override suspend fun search(
        query: String,
        page: Int,
    ): List<AppAdapter.Item> =
        tmdb.search(query, page).map(::markItem)

    override suspend fun getMovies(
        page: Int,
    ): List<Movie> =
        tmdb.getMovies(page).map(::markMovie)

    override suspend fun getTvShows(
        page: Int,
    ): List<TvShow> =
        tmdb.getTvShows(page).map(::markTvShow)

    override suspend fun getMovie(
        id: String,
    ): Movie =
        markMovie(tmdb.getMovie(id))

    override suspend fun getTvShow(
        id: String,
    ): TvShow =
        markTvShow(tmdb.getTvShow(id))

    override suspend fun getEpisodesBySeason(
        seasonId: String,
    ): List<Episode> =
        tmdb.getEpisodesBySeason(seasonId)

    override suspend fun getGenre(
        id: String,
        page: Int,
    ): Genre {
        val genre = tmdb.getGenre(id, page)

        return genre.copy(
            shows = genre.shows.map {
                when (it) {
                    is Movie -> markMovie(it)
                    is TvShow -> markTvShow(it)
                }
            }
        )
    }

    override suspend fun getPeople(
        id: String,
        page: Int,
    ): People {
        val person = tmdb.getPeople(id, page)

        return person.copy(
            filmography = person.filmography.map {
                when (it) {
                    is Movie -> markMovie(it)
                    is TvShow -> markTvShow(it)
                }
            }
        )
    }

    override suspend fun getServers(
        id: String,
        videoType: Video.Type,
    ): List<Video.Server> = coroutineScope {

        /*
         * These correspond to NetPrime's currently exposed server set,
         * but use BetterStreamflix's native resolver paths where available.
         *
         * This is more reliable than feeding NetPrime iframe URLs into
         * extractors that expect their provider-specific API format.
         */

        val vidrockDeferred = async(Dispatchers.IO) {
            runCatching {
                VidrockExtractor()
                    .servers(videoType)
                    .map { it.asNetPrime("VidRock") }
            }.getOrDefault(emptyList())
        }

        val videasyDeferred = async(Dispatchers.IO) {
            runCatching {
                VideasyExtractor()
                    .servers(videoType, "en")
                    .map { it.asNetPrime(it.name) }
            }.getOrDefault(emptyList())
        }

        val servers = mutableListOf<Video.Server>()

        /*
         * Vidnest is the one source where NetPrime's current iframe
         * domain differs from BetterStreamflix's older extractor mainUrl.
         * The .fun domain is registered as an extractor alias below.
         */
        servers += Video.Server(
            id = "netprime-vidnest",
            name = "NetPrime • Vidnest",
            src = when (videoType) {
                is Video.Type.Movie ->
                    "https://vidnest.fun/movie/${videoType.id}"

                is Video.Type.Episode ->
                    "https://vidnest.fun/tv/" +
                        "${videoType.tvShow.id}/" +
                        "${videoType.season.number}/" +
                        "${videoType.number}"
            }
        )

        servers += VidLinkExtractor()
            .server(videoType)
            .asNetPrime("VidLink")

        servers += TwoEmbedExtractor()
            .server(videoType)
            .asNetPrime("2Embed")

        servers += vidrockDeferred.await()
        servers += videasyDeferred.await()

        servers.distinctBy { it.id }
    }

    override suspend fun getVideo(
        server: Video.Server,
    ): Video {
        server.video?.let { return it }

        return Extractor.extract(
            server.src,
            server,
        )
    }

    private fun Video.Server.asNetPrime(
        label: String,
    ): Video.Server =
        copy(
            id = "netprime-$id",
            name = if (
                label.startsWith("NetPrime")
            ) {
                label
            } else {
                "NetPrime • $label"
            }
        )

    private fun markItem(
        item: AppAdapter.Item,
    ): AppAdapter.Item =
        when (item) {
            is Movie -> markMovie(item)
            is TvShow -> markTvShow(item)
            else -> item
        }

    private fun markMovie(
        movie: Movie,
    ): Movie =
        movie

    private fun markTvShow(
        tvShow: TvShow,
    ): TvShow =
        tvShow
}
