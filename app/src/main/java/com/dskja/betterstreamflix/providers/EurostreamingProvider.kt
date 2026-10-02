package com.dskja.betterstreamflix.providers

import android.util.Log
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.extractors.Extractor
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Genre
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.People
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.models.Show
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.utils.DnsResolver
import com.dskja.betterstreamflix.utils.TmdbUtils
import com.dskja.betterstreamflix.utils.UserPreferences
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object EurostreamingProvider : Provider, ProviderConfigUrl {

    private const val TAG = "Eurostreaming"
    private const val USER_AGENT =
        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    override val name = "Eurostreaming"
    override val defaultBaseUrl = "https://eurostreaming.design/"
    override val baseUrl: String
        get() = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            .ifBlank { defaultBaseUrl }
    override val changeUrlMutex = Mutex()
    override val logo: String
        get() = "https://www.google.com/s2/favicons?domain=eurostreaming.design&sz=128"
    override val language = "it"

    override suspend fun onChangeUrl(forceRefresh: Boolean): String = changeUrlMutex.withLock {
        service = EurostreamingService.build(normalizedBase())
        baseUrl
    }

    private interface EurostreamingService {
        companion object {
            fun build(baseUrl: String): EurostreamingService {
                val client = OkHttpClient.Builder()
                    .readTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(45, TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        val origin = baseUrl.trimEnd('/')
                        val request = chain.request().newBuilder()
                            .header(
                                "User-Agent",
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                            )
                            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                            .header("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
                            .header("Referer", "$origin/")
                            .build()
                        chain.proceed(request)
                    }
                    .dns(DnsResolver.doh)
                    .build()
                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()
                    .create(EurostreamingService::class.java)
            }
        }

        @Headers(USER_AGENT)
        @GET(".")
        suspend fun getHome(): Document

        @Headers(USER_AGENT)
        @GET
        suspend fun getPage(@Url url: String): Document
    }

    private var service = EurostreamingService.build(defaultBaseUrl)

    private fun normalizedBase(): String =
        baseUrl.let { if (it.endsWith("/")) it else "$it/" }

    private suspend fun getDocument(url: String): Document {
        return try {
            service.getPage(url)
        } catch (e: Exception) {
            Log.e(TAG, "getDocument failed for $url: ${e.message}")
            throw e
        }
    }

    private fun parseItems(doc: Document): List<Show> {
        return ItalianVixCatalog.parsePostThumbItems(baseUrl, doc)
            .ifEmpty { ItalianVixCatalog.parseDetailLinks(baseUrl, doc) }
    }

    override suspend fun getHome(): List<Category> {
        return try {
            val categories = mutableListOf<Category>()
            val home = getDocument(normalizedBase())
            val homeItems = parseItems(home)
            if (homeItems.isNotEmpty()) {
                categories += Category(name = Category.FEATURED, list = homeItems.take(24))
                val films = homeItems.filterIsInstance<Movie>()
                val series = homeItems.filterIsInstance<TvShow>()
                if (films.isNotEmpty()) categories += Category(name = "Film", list = films)
                if (series.isNotEmpty()) categories += Category(name = "Serie TV", list = series)
            }

            runCatching {
                val archiveTv = parseItems(getDocument("${normalizedBase()}archive?type=tv&sort=popularity"))
                    .filterIsInstance<TvShow>()
                if (archiveTv.isNotEmpty()) {
                    categories += Category(name = "Serie popolari", list = archiveTv)
                }
            }
            runCatching {
                val archiveMovies = parseItems(getDocument("${normalizedBase()}archive?type=movie&sort=popularity"))
                    .filterIsInstance<Movie>()
                if (archiveMovies.isNotEmpty()) {
                    categories += Category(name = "Film popolari", list = archiveMovies)
                }
            }

            categories.distinctBy { it.name }
        } catch (e: Exception) {
            Log.e(TAG, "getHome failed: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            return try {
                val doc = getDocument(normalizedBase())
                doc.select("a[href*=genre_id=]").mapNotNull { a ->
                    val href = ItalianVixCatalog.absUrl(baseUrl, a.attr("href"))
                    val text = a.text().trim()
                    if (href.isBlank() || text.isBlank()) null else Genre(id = href, name = text)
                }.distinctBy { it.id }
            } catch (_: Exception) {
                emptyList()
            }
        }

        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = buildString {
                append(normalizedBase().trimEnd('/'))
                append("/search?q=")
                append(encoded)
                if (page > 1) append("&page=$page")
            }
            parseItems(getDocument(url))
        } catch (e: Exception) {
            Log.e(TAG, "search failed: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return try {
            val url = if (page > 1) {
                "${normalizedBase()}archive?type=movie&page=$page&sort=popularity"
            } else {
                "${normalizedBase()}archive?type=movie&sort=popularity"
            }
            parseItems(getDocument(url)).filterIsInstance<Movie>()
        } catch (e: Exception) {
            Log.e(TAG, "getMovies failed: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            val url = if (page > 1) {
                "${normalizedBase()}archive?type=tv&page=$page&sort=popularity"
            } else {
                "${normalizedBase()}archive?type=tv&sort=popularity"
            }
            parseItems(getDocument(url)).filterIsInstance<TvShow>()
        } catch (e: Exception) {
            Log.e(TAG, "getTvShows failed: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun getMovie(id: String): Movie {
        val doc = getDocument(id)
        val title = ItalianVixCatalog.cleanTitle(
            doc.selectFirst("h1, .section-title h2, .es-detail-grid h2")?.text().orEmpty(),
        )
        val tmdb = TmdbUtils.getMovie(title, language = language)
        val poster = tmdb?.poster
            ?: doc.selectFirst(".es-detail-poster img, img[src*=/img/]")?.attr("src")
                ?.let { ItalianVixCatalog.absUrl(baseUrl, it) }
            ?: ""
        return Movie(
            id = id,
            title = title.ifBlank { id.substringAfterLast('/') },
            poster = poster,
            overview = tmdb?.overview
                ?: doc.selectFirst(".es-detail-overview, .description, p.f-desc")?.text()?.trim(),
            rating = tmdb?.rating,
            banner = tmdb?.banner,
            imdbId = tmdb?.imdbId,
            runtime = tmdb?.runtime,
            genres = tmdb?.genres ?: emptyList(),
            cast = emptyList(),
            trailer = tmdb?.trailer
                ?: doc.selectFirst("iframe[src*=youtube]")?.attr("src"),
            providerName = name,
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val doc = getDocument(id)
        val title = ItalianVixCatalog.cleanTitle(
            doc.selectFirst("h1, .section-title h2, .es-detail-grid h2")?.text().orEmpty(),
        )
        val tmdb = TmdbUtils.getTvShow(title, language = language)
        val poster = tmdb?.poster
            ?: doc.selectFirst(".es-detail-poster img, img[src*=/img/]")?.attr("src")
                ?.let { ItalianVixCatalog.absUrl(baseUrl, it) }
            ?: ""
        val seasons = ItalianVixCatalog.seasonsFromDetail(doc, id).map { season ->
            Season(
                id = season.id,
                number = season.number,
                poster = tmdb?.seasons?.find { it.number == season.number }?.poster,
            )
        }
        return TvShow(
            id = id,
            title = title.ifBlank { id.substringAfterLast('/') },
            poster = poster,
            overview = tmdb?.overview
                ?: doc.selectFirst(".es-detail-overview, .description, p.f-desc")?.text()?.trim(),
            rating = tmdb?.rating,
            genres = tmdb?.genres ?: emptyList(),
            cast = emptyList(),
            seasons = seasons,
            banner = tmdb?.banner,
            imdbId = tmdb?.imdbId,
            trailer = tmdb?.trailer,
            runtime = tmdb?.runtime,
            providerName = name,
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        if ("|" !in seasonId) return emptyList()
        val showId = seasonId.substringBefore("|")
        val seasonNum = seasonId.substringAfter("|").toIntOrNull() ?: return emptyList()
        return try {
            ItalianVixCatalog.episodesForSeason(getDocument(showId), showId, seasonNum, language)
        } catch (e: Exception) {
            Log.e(TAG, "getEpisodesBySeason failed: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        return try {
            val url = when {
                id.contains("page=") -> id.replace(Regex("page=\\d+"), "page=$page")
                page > 1 -> id + (if ("?" in id) "&" else "?") + "page=$page"
                else -> id
            }
            Genre(id = id, name = "", shows = parseItems(getDocument(url)))
        } catch (_: Exception) {
            ProviderDefaults.emptyGenre(id)
        }
    }

    override suspend fun getPeople(id: String, page: Int): People {
        if (page > 1) return ProviderDefaults.emptyPeople(id)
        return try {
            People(id = id, name = "", filmography = parseItems(getDocument(id)))
        } catch (_: Exception) {
            ProviderDefaults.emptyPeople(id)
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return try {
            val pageId = id.substringBefore("|").ifBlank { id }
            ItalianVixCatalog.serversFor(getDocument(pageId), id, videoType)
        } catch (e: Exception) {
            Log.e(TAG, "getServers failed: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.src.ifBlank { server.id }, server)
    }
}
