package com.dskja.betterstreamflix.providers

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.dskja.betterstreamflix.BetterStreamflixApp
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.database.AniWorldDatabase
import com.dskja.betterstreamflix.database.dao.TvShowDao
import com.dskja.betterstreamflix.extractors.Extractor
import com.dskja.betterstreamflix.extractors.canonicalHosterUrl
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Genre
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.People
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.providers.SerienStreamProvider.SerienStreamService
import com.dskja.betterstreamflix.utils.AniWorldUpdateTvShowWorker
import com.dskja.betterstreamflix.utils.DnsResolver
import com.dskja.betterstreamflix.utils.TmdbUtils
import com.dskja.betterstreamflix.utils.UserPreferences
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.dnsoverhttps.DnsOverHttps
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Url
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object AniWorldProvider : Provider, ProviderConfigUrl {


    private const val URL = "https://aniworld.to/"
    override val defaultBaseUrl = "https://aniworld.to/"
    override val baseUrl: String
        get() = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL).ifBlank { defaultBaseUrl }
    override val changeUrlMutex = Mutex()

    override suspend fun onChangeUrl(forceRefresh: Boolean): String = changeUrlMutex.withLock {
        baseUrl
    }

    override val name = "AniWorld"
    override val logo = "$URL/public/img/facebook.jpg"
    override val language = "de"

    private val service = Service.build()

    private var tvShowDao: TvShowDao? = null
    private var isWorkerScheduled = false
    private lateinit var appContext: Context

    private var preloadJob: Job? = null
    private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val cacheLock = Any()

    fun initialize(context: Context) {
        if (AniWorldProvider.tvShowDao == null) {
            AniWorldProvider.tvShowDao = AniWorldDatabase.getInstance(context).tvShowDao()

            this.appContext = context.applicationContext

        }
        if (!AniWorldProvider.isWorkerScheduled) {
            scheduleUpdateWorker(context)
            AniWorldProvider.isWorkerScheduled = true
        }
    }


    private fun scheduleUpdateWorker(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<AniWorldUpdateTvShowWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "AniWorldUpdateTvShowWorker",
            ExistingWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun getDao(): TvShowDao {
        return tvShowDao ?: throw IllegalStateException("AniWorldProvider not initialized")
    }

    override suspend fun getHome(): List<Category> {
        preloadSeriesAlphabetAsync()
        val document = service.getHome()
        return AniWorldHtml.homeShelves(document, URL).filter { it.list.isNotEmpty() }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) {
            return try {
                val document = service.getGenres()
                document.select("#seriesContainer h3").mapNotNull {
                    val name = it.text().trim()
                    if (name.isBlank()) return@mapNotNull null
                    Genre(
                        id = name.lowercase(Locale.getDefault()),
                        name = name,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                emptyList()
            }
        }

        val lowerQuery = query.trim().lowercase(Locale.getDefault())
        if (lowerQuery.isBlank()) return emptyList()
        val limit = chunkSize
        val offset = (page - 1) * chunkSize

        // Prefer local catalog; fall back to live AJAX so Search never throws / kills TV.
        val local = runCatching {
            getDao().searchTvShows(lowerQuery, limit, offset)
        }.getOrElse { emptyList() }
            .filter { it.id.isNotBlank() && it.title.isNotBlank() }

        if (local.isNotEmpty() || page > 1) return local

        return try {
            service.search(query)
                .mapNotNull { item ->
                    val link = item.link.trim()
                    val title = item.title.trim()
                    if (link.isBlank() || title.isBlank()) return@mapNotNull null
                    val id = link.substringAfter("/anime/stream/").substringBefore('/').trim()
                    if (id.isBlank()) return@mapNotNull null
                    TvShow(id = id, title = title)
                }
                .distinctBy { it.id }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        throw Exception("Keine Filme verfügbar")
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val fromIndex = (page - 1) * chunkSize
        val toIndex = page * chunkSize

        if (!isSeriesCacheLoaded) {
            var cachedShows = emptyList<TvShow>()
            try {
                cachedShows = getDao().getAll().first()
            } catch (exception: Exception) {
                // ignore for now
            }
            if (cachedShows.isNotEmpty()) {
                synchronized(cacheLock) {
                    seriesCache.clear()
                    seriesCache.addAll(cachedShows)
                    isSeriesCacheLoaded = true
                }
            } else {
                preloadSeriesAlphabet()
            }
        }
        preloadSeriesAlphabetAsync()
        synchronized(cacheLock) {
            if (fromIndex >= seriesCache.size) return emptyList()
            val actualToIndex = minOf(toIndex, seriesCache.size)
            return seriesCache.subList(fromIndex, actualToIndex).toList()
        }
    }

    override suspend fun getMovie(id: String): Movie {
        throw Exception("Keine Filme verfügbar")
    }

    override suspend fun getTvShow(id: String): TvShow {
        val document = service.getAnime(id)

        val tvShow = TvShow(
            id = id,
            title = document.selectFirst("h1 > span")
                ?.text()
                ?: "",
            overview = document.selectFirst("p.seri_des")
                ?.attr("data-full-description"),
            released = document.selectFirst("div.series-title > small > span:nth-child(1)")
                ?.text()
                ?: "",
            trailer = document.selectFirst("div[itemprop='trailer'] a")
                ?.attr("href"),
            poster = absoluteMedia(
                document.selectFirst("div.seriesCoverBox img")?.attr("data-src"),
            ),
            banner = document.selectFirst("#series > section > div.backdrop")
                ?.attr("style")
                ?.replace("background-image: url(/", "")?.replace(")", "")
                ?.let { URL + it },


            seasons = document.select("#stream > ul:nth-child(1) > li")
                .filter { it.select("a").isNotEmpty() }
                .mapIndexedNotNull { index, it ->
                    val seasonText = it.selectFirst("a")?.text() ?: ""
                    val seasonNumber = when {
                        seasonText.contains("Filme", true) || seasonText.contains("Specials", true) -> 0
                        else -> Regex("""\d+""").find(seasonText)?.value?.toIntOrNull() ?: (index + 1)
                    }
                    val seasonId = it.selectFirst("a")
                        ?.attr("href")?.substringAfter("/anime/stream/")
                        ?.trim().orEmpty()
                    val seasonTitle = it.selectFirst("a")?.attr("title")?.trim().orEmpty()
                        .ifBlank { seasonText.trim() }
                    if (seasonId.isBlank() || seasonTitle.isBlank()) return@mapIndexedNotNull null
                    Season(
                        id = seasonId,
                        number = seasonNumber,
                        title = seasonTitle,
                    )
                },
            genres = document.select(".genres li").map {
                Genre(
                    id = it.selectFirst("a")
                        ?.text()?.lowercase(Locale.getDefault())
                        ?: "",
                    name = it.selectFirst("a")
                        ?.text()
                        ?: "",
                )
            },
            directors = document.select(".cast li[itemprop='director']").mapNotNull {
                val id = getPeopleIdFromLink(
                    it.selectFirst("a")?.attr("href").orEmpty(),
                )
                val name = it.selectFirst("span")?.text().orEmpty()
                if (id.isBlank() || name.isBlank()) return@mapNotNull null
                People(id = id, name = name)
            },
            cast = document.select(".cast li[itemprop='actor']").mapNotNull {
                val id = getPeopleIdFromLink(
                    it.selectFirst("a")?.attr("href").orEmpty(),
                )
                val name = it.selectFirst("span")?.text().orEmpty()
                if (id.isBlank() || name.isBlank()) return@mapNotNull null
                People(id = id, name = name)
            },
        )

        val tmdbTvShow = TmdbUtils.getTvShow(tvShow.title, language = language)

        return tvShow.copy(
            overview = tmdbTvShow?.overview ?: tvShow.overview,
            rating = tmdbTvShow?.rating ?: tvShow.rating,
            trailer = tmdbTvShow?.trailer ?: tvShow.trailer,
            banner = tmdbTvShow?.banner ?: tvShow.banner,
            imdbId = tmdbTvShow?.imdbId,
            seasons = tvShow.seasons.map { season ->
                season.copy(
                    poster = tmdbTvShow?.seasons?.find { it.number == season.number }?.poster
                )
            },
            cast = tvShow.cast.map { person ->
                val tmdbPerson = tmdbTvShow?.cast?.find { it.name.equals(person.name, ignoreCase = true) }
                person.copy(image = tmdbPerson?.image)
            }
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split("/")
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return emptyList()
        val tvShowId = parts[0]
        val season = parts[1]

        val document = service.getSeason(tvShowId, season)

        val tmdbTvShow = TmdbUtils.getTvShow(document.selectFirst("h1 > span")?.text() ?: "", language = language)
        val seasonNumber = when {
            season.contains("Filme", true) || season.contains("Specials", true) -> 0
            else -> Regex("""\d+""").find(season)?.value?.toIntOrNull() ?: 1
        }
        val tmdbEpisodes = tmdbTvShow?.let { TmdbUtils.getEpisodesBySeason(it.id, seasonNumber, language = language) } ?: emptyList()

        val episodes = document.select("tbody tr").mapNotNull {
            val epNumber = it.selectFirst("meta")?.attr("content")?.toIntOrNull() ?: 0
            val tmdbEp = tmdbEpisodes.find { ep -> ep.number == epNumber }
            val episodeId = it.selectFirst("a")
                ?.attr("href")?.substringAfter("/anime/stream/")
                ?.trim().orEmpty()
            val title = tmdbEp?.title ?: it.selectFirst("strong")?.text()?.trim().orEmpty()
            if (episodeId.isBlank() || title.isBlank()) return@mapNotNull null
            Episode(
                id = episodeId,
                number = epNumber,
                title = title,
                poster = tmdbEp?.poster,
                overview = tmdbEp?.overview
            )
        }

        return episodes
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        if (page > 1) return Genre(id, "")

        val document = service.getGenre(id, page)

        val genre = Genre(
            id = id,
            name = document.selectFirst("h1")
                ?.text()?.substringBefore(" Animes")
                ?: "",

            shows = document.select(".seriesListContainer > div").mapNotNull {
                val showId = it.selectFirst("a")
                    ?.attr("href")?.substringAfter("/anime/stream/")
                    ?.trim().orEmpty()
                val title = it.selectFirst("h3")?.text()?.trim().orEmpty()
                    .ifBlank {
                        it.selectFirst("a")?.attr("title")?.substringBefore(" stream")?.trim().orEmpty()
                    }
                if (showId.isBlank() || title.isBlank()) return@mapNotNull null
                val img = it.selectFirst("img")
                val rawPoster = img?.attr("data-src")?.ifBlank { img.attr("src") }
                TvShow(
                    id = showId,
                    title = title,
                    poster = absoluteMedia(rawPoster),
                )
            }
        )

        return genre
    }

    override suspend fun getPeople(id: String, page: Int): People {
        if (page > 1) return People(id, "")

        val peopleId = normalizePeopleId(id)
        if (peopleId.isBlank()) return People(id, "")
        val document = service.getPeople(peopleId)

        val people = People(
            id = peopleId,
            name = document.selectFirst("h1 strong")
                ?.text()
                ?: document.selectFirst("h1")?.text()
                ?: "",
            image = absoluteMedia(
                document.selectFirst(".seriesCoverBox img, .person-image img, img")?.attr("data-src"),
            ),
            biography = document.selectFirst("p.seri_des, .series-description p, span.description-text")
                ?.let { el -> el.attr("data-full-description").ifBlank { el.text() } }
                ?.takeIf { it.isNotBlank() },

            filmography = document.select(".seriesListContainer > div").mapNotNull {
                val showId = it.selectFirst("a")
                    ?.attr("href")?.substringAfter("/anime/stream/")
                    .orEmpty()
                val title = it.selectFirst("h3")?.text().orEmpty()
                if (showId.isBlank() || title.isBlank()) return@mapNotNull null
                TvShow(
                    id = showId,
                    title = title,
                    poster = absoluteMedia(it.selectFirst("img")?.attr("data-src")),
                )
            }
        )

        return people
    }

    private fun absoluteMedia(raw: String?): String? {
        val src = raw?.trim().orEmpty()
        if (src.isBlank() || src.startsWith("data:", ignoreCase = true)) return null
        if (src.startsWith("http://", ignoreCase = true) || src.startsWith("https://", ignoreCase = true)) {
            return src
        }
        if (src.startsWith("//")) return "https:$src"
        return URL.trimEnd('/') + "/" + src.removePrefix("/")
    }

    private fun getPeopleIdFromLink(link: String): String {
        return normalizePeopleId(link)
    }

    private fun normalizePeopleId(raw: String): String {
        val href = raw.trim()
        if (href.isBlank()) return ""
        return when {
            href.contains("/animes/", ignoreCase = true) ->
                href.substringAfter("/animes/", missingDelimiterValue = "")
                    .substringBefore('?')
                    .trim('/')
            href.contains("/cast/", ignoreCase = true) ->
                href.substringAfter("/cast/", missingDelimiterValue = "")
                    .substringBefore('?')
                    .trim('/')
            href.contains("/person/", ignoreCase = true) ->
                href.substringAfter("/person/", missingDelimiterValue = "")
                    .substringBefore('?')
                    .trim('/')
            else -> href.substringAfterLast('/').substringBefore('?').trim()
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val parts = id.split("/")
        if (parts.size < 3 || parts.any { it.isBlank() }) return emptyList()
        val tvShowId = parts[0]
        val seasonId = parts[1]
        val episodeId = parts[2]

        val document = service.getEpisode(tvShowId, seasonId, episodeId)

        val servers = document.select("div.hosterSiteVideo > ul > li").mapNotNull {
            val redirectUrl = it.selectFirst("a")
                ?.attr("href")?.let { href -> URL + href }
                ?: return@mapNotNull null

            val name = it.selectFirst("h4")
                ?.text()?.let { name ->
                    name + when (it.attr("data-lang-key")) {
                        "1" -> " - DUB"
                        "2" -> " - SUB English"
                        "3" -> " - SUB"
                        else -> ""
                    }
                }
                ?: ""

            Video.Server(
                id = name,
                name = name,
                src = redirectUrl,
            )
        }

        return servers
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val response = service.getRedirectLink(server.src)
            .let { response -> response.raw() as okhttp3.Response }
        val videoUrl = response.request.url

        val link = canonicalHosterUrl(
            serverName = server.name,
            resolvedUrl = videoUrl.toString(),
            encodedPath = videoUrl.encodedPath,
        )
        if (link.isBlank()) throw Exception("AniWorld source not found")

        return Extractor.extract(link, server)
    }


    private val seriesCache = mutableListOf<TvShow>()
    private const val chunkSize = 25
    private var isSeriesCacheLoaded = false

    private fun preloadSeriesAlphabetAsync() {
        if (preloadJob?.isActive == true) return
        preloadJob = providerScope.launch {
            runCatching { preloadSeriesAlphabet() }
        }
    }

    private suspend fun preloadSeriesAlphabet() {
        val document = service.getAnimesAlphabet()
        val elements = document.select(".genre > ul > li")

        val loadedShows = elements.map {
            TvShow(
                id = it.selectFirst("a[data-alternative-title]")
                    ?.attr("href")?.substringAfter("/anime/stream/")
                    ?: "",
                title = it.selectFirst("a[data-alternative-title]")
                    ?.text()
                    ?: "",
                overview = "",
            )
        }
        val dao = getDao()
        val existingIds = dao.getAllIds()
        val newShows = loadedShows.filter { it.id !in existingIds }

        if (newShows.isNotEmpty()) {
            dao.insertAll(newShows)
        }
        val allShows = dao.getAll().first()
        synchronized(cacheLock) {
            seriesCache.clear()
            seriesCache.addAll(allShows)
            isSeriesCacheLoaded = true
        }

        scheduleUpdateWorker(appContext)
    }

    fun invalidateCache() {
        synchronized(cacheLock) {
            seriesCache.clear()
            isSeriesCacheLoaded = false
        }
    }

    fun getSeriesChunk(pageIndex: Int): List<TvShow> {
        val fromIndex = pageIndex * chunkSize
        if (fromIndex >= seriesCache.size) return emptyList()
        val toIndex = minOf(fromIndex + chunkSize, seriesCache.size)
        return seriesCache.subList(fromIndex, toIndex)
    }

    fun getTotalPages(): Int {
        return (seriesCache.size + chunkSize - 1) / chunkSize
    }


    private interface Service {
        companion object {
            private fun isAniWorldHost(host: String?): Boolean {
                val h = host?.lowercase(Locale.US)?.removePrefix("www.").orEmpty()
                if (h.isBlank()) return false
                return h == "aniworld.to" || h.endsWith(".aniworld.to") || h.contains("aniworld")
            }

            private fun OkHttpClient.Builder.applyAniWorldSession(): OkHttpClient.Builder {
                return cookieJar(com.dskja.betterstreamflix.utils.NetworkClient.cookieJar)
                    .addInterceptor { chain ->
                        val original = chain.request()
                        val builder = original.newBuilder()
                        val stored = AniWorldAuthManager.cookieHeaderForRequests()
                        if (stored.isNotBlank() && isAniWorldHost(original.url.host)) {
                            val merged = com.dskja.betterstreamflix.player.SerienStreamBypassHelper
                                .mergeOutgoingCookieHeader(original.header("Cookie"), stored)
                            if (merged.isNotBlank()) {
                                builder.header("Cookie", merged)
                            }
                        }
                        chain.proceed(builder.build())
                    }
            }

            private fun getOkHttpClient(): OkHttpClient {
                val appCache = Cache(File(BetterStreamflixApp.instance.cacheDir, "okhttpcache"), 10 * 1024 * 1024)
                val clientBuilder = OkHttpClient.Builder()
                    .cache(appCache)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(45, TimeUnit.SECONDS)
                return clientBuilder
                    .dns(DnsResolver.doh)
                    .applyAniWorldSession()
                    .build()
            }

            private fun getUnsafeOkHttpClient(): OkHttpClient {
                try {
                    val trustAllCerts = arrayOf<TrustManager>(
                        object : X509TrustManager {
                            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                        }
                    )
                    val sslContext = SSLContext.getInstance("SSL")
                    sslContext.init(null, trustAllCerts, SecureRandom())
                    val sslSocketFactory = sslContext.socketFactory

                    val appCache = Cache(File(BetterStreamflixApp.instance.cacheDir, "okhttpcache"), 10 * 1024 * 1024)
                    val clientBuilder = OkHttpClient.Builder()
                        .cache(appCache)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .callTimeout(45, TimeUnit.SECONDS)
                        .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
                        .hostnameVerifier { _, _ -> true }

                    return clientBuilder
                        .dns(DnsResolver.doh)
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .applyAniWorldSession()
                        .build()
                } catch (e: Exception) {
                    throw RuntimeException(e)
                }
            }

            fun build(): Service {
                val client = getOkHttpClient()
                val retrofit = Retrofit.Builder()
                    .baseUrl(URL)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(client)
                    .build()
                return retrofit.create(Service::class.java)
            }

            fun buildUnsafe(): Service {
                val client = getUnsafeOkHttpClient()
                val retrofit = Retrofit.Builder()
                    .baseUrl(URL)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(client)
                    .build()
                return retrofit.create(Service::class.java)
            }
        }

        @GET(".")
        suspend fun getHome(): Document

        @POST("https://aniworld.to/ajax/search")
        @FormUrlEncoded
        suspend fun search(@Field("keyword") query: String): List<SearchItem>

        @GET("animes-genres")
        suspend fun getGenres(): Document

        @GET("animes-alphabet")
        suspend fun getAnimesAlphabet(): Document

        @GET("anime/stream/{id}")
        suspend fun getAnime(@Path("id") id: String): Document

        @GET("anime/stream/{tvShowId}/{seasonId}")
        suspend fun getSeason(
            @Path("tvShowId") tvShowId: String,
            @Path("seasonId") seasonId: String,
        ): Document

        @GET("genre/{id}/{page}")
        suspend fun getGenre(
            @Path("id") id: String,
            @Path("page") page: Int,
        ): Document

        @GET("animes/{id}")
        suspend fun getPeople(@Path("id", encoded = true) id: String): Document

        @GET("anime/stream/{tvShowId}/{seasonId}/{episodeId}")
        suspend fun getEpisode(
            @Path("tvShowId") tvShowId: String,
            @Path("seasonId") seasonId: String,
            @Path("episodeId") episodeId: String,
        ): Document

        @GET
        @Headers("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        suspend fun getRedirectLink(@Url url: String): Response<ResponseBody>


        data class SearchItem(
            val title: String,
            val description: String,
            val link: String,
        )
    }
}
