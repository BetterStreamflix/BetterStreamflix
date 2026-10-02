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
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query
import retrofit2.http.Url
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Classic DLE Altadefinizione mirror (altadefinizionex.me) with VixSrc (IMDb) + VidxGo embeds.
 * Kept separate from [AltadefinizioneProvider] (modern beer stack) and [Altadefinizione01Provider].
 */
object AltadefinizioneXProvider : Provider, ProviderConfigUrl {

    private const val TAG = "AltadefinizioneX"
    private const val USER_AGENT =
        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val VIXSRC = "https://vixsrc.to"
    private const val VIDXGO = "https://v.vidxgo.co"

    override val name = "Altadefinizione X"
    override val defaultBaseUrl = "https://altadefinizionex.me/"
    override val baseUrl: String
        get() = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            .ifBlank { defaultBaseUrl }
    override val changeUrlMutex = Mutex()
    override val logo: String
        get() = "https://www.google.com/s2/favicons?domain=altadefinizionex.me&sz=128"
    override val language = "it"

    override suspend fun onChangeUrl(forceRefresh: Boolean): String = changeUrlMutex.withLock {
        service = Service.build(normalizedBase())
        baseUrl
    }

    private interface Service {
        companion object {
            fun build(baseUrl: String): Service {
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
                            .header("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")
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
                    .create(Service::class.java)
            }
        }

        @Headers(USER_AGENT)
        @GET(".")
        suspend fun getHome(): Document

        @Headers(USER_AGENT)
        @GET("index.php")
        suspend fun search(
            @Query("do") doParam: String = "search",
            @Query("subaction") subaction: String = "search",
            @Query(value = "story", encoded = true) story: String,
        ): Document

        @Headers(USER_AGENT)
        @GET
        suspend fun getPage(@Url url: String): Document
    }

    private var service = Service.build(defaultBaseUrl)

    private fun normalizedBase(): String =
        baseUrl.let { if (it.endsWith("/")) it else "$it/" }

    private fun absUrl(path: String): String = ItalianVixCatalog.absUrl(baseUrl, path)

    private fun isTvUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/serie-tv/") ||
            lower.contains("/miniserie-tv/") ||
            lower.contains("/tv-show/") ||
            lower.contains("serie-tv") ||
            lower.contains("tipo=2")
    }

    private fun parseMovieCard(el: Element): Show? {
        val link = el.attr("data-link").ifBlank {
            el.selectFirst("a[href]")?.attr("href").orEmpty()
        }
        if (link.isBlank() || !link.contains(".html")) return null
        val absolute = absUrl(link)
        val title = el.selectFirst(".movie-title a, h2.movie-title a, .movie-title")?.text()?.trim()
            .orEmpty()
            .ifBlank {
                absolute.substringAfterLast('/')
                    .substringBefore(".html")
                    .substringAfter('-')
                    .replace('-', ' ')
            }
        if (title.isBlank()) return null
        val poster = el.selectFirst("img")?.attr("src")?.let { absUrl(it) }.orEmpty()
        val rating = el.attr("data-imdb").toDoubleOrNull()
        return if (isTvUrl(absolute)) {
            TvShow(id = absolute, title = title, poster = poster, rating = rating)
        } else {
            Movie(id = absolute, title = title, poster = poster, rating = rating)
        }
    }

    private fun parseItems(root: Element): List<Show> {
        val fromCards = root.select("div.movie[data-link], div.movie").mapNotNull { parseMovieCard(it) }
        if (fromCards.isNotEmpty()) return fromCards.distinctBy { ItalianVixCatalog.showId(it) }
        val host = runCatching { baseUrl.trimEnd('/').substringAfter("://").substringBefore('/') }
            .getOrDefault("altadefinizionex.me")
        return root.select("a[href$=.html]").mapNotNull { a ->
            val href = absUrl(a.attr("href"))
            if (!href.endsWith(".html")) return@mapNotNull null
            if (!href.contains(host) && !href.contains("altadefinizione")) return@mapNotNull null
            val title = a.text().trim().ifBlank { a.selectFirst("img")?.attr("alt").orEmpty() }
            if (title.isBlank() || title.length < 2) return@mapNotNull null
            val poster = a.selectFirst("img")?.attr("src")?.let { absUrl(it) }.orEmpty()
            if (isTvUrl(href)) TvShow(id = href, title = title, poster = poster)
            else Movie(id = href, title = title, poster = poster)
        }.distinctBy { ItalianVixCatalog.showId(it) }
    }

    private fun extractImdbId(doc: Document): String? {
        val html = doc.html()
        Regex("""imdb\s*=\s*['"](tt\d+)['"]""").find(html)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("""tt(\d{5,})""").find(html)?.groupValues?.getOrNull(1)?.let { return "tt$it" }
        Regex("""vixsrc\.to/(?:movie|tv)/(tt\d+)""").find(html)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("""v\.vidxgo\.co/(tt\d+)""").find(html)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    override suspend fun getHome(): List<Category> {
        return try {
            val doc = service.getHome()
            val categories = mutableListOf<Category>()
            doc.select(".section-title, h4.section-title, .fw-bold").forEach { titleEl ->
                val title = titleEl.ownText().ifBlank { titleEl.text() }.trim()
                if (title.isBlank() || title.length > 40) return@forEach
                val section = titleEl.parent()?.parent() ?: titleEl.parent() ?: return@forEach
                val items = parseItems(section)
                if (items.isNotEmpty()) categories += Category(name = title, list = items)
            }
            if (categories.isEmpty()) {
                val items = parseItems(doc)
                if (items.isNotEmpty()) {
                    categories += Category(name = Category.FEATURED, list = items.take(30))
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
            return listOf(
                Genre(id = absUrl("/film/?tipo=1"), name = "Film"),
                Genre(id = absUrl("/film/?tipo=2"), name = "Serie TV"),
                Genre(id = absUrl("/cinema/"), name = "Cinema"),
                Genre(id = absUrl("/serie-tv/"), name = "Serie TV archive"),
            )
        }
        if (page > 1) return emptyList()
        return try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            parseItems(service.search(story = encoded))
        } catch (e: Exception) {
            Log.e(TAG, "search failed: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return try {
            val url = if (page > 1) {
                "${normalizedBase()}film/page/$page/?tipo=1"
            } else {
                "${normalizedBase()}film/?tipo=1"
            }
            parseItems(service.getPage(url)).filterIsInstance<Movie>()
        } catch (e: Exception) {
            Log.e(TAG, "getMovies failed: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            val url = if (page > 1) {
                "${normalizedBase()}serie-tv/page/$page/"
            } else {
                "${normalizedBase()}serie-tv/"
            }
            parseItems(service.getPage(url)).filterIsInstance<TvShow>()
        } catch (e: Exception) {
            Log.e(TAG, "getTvShows failed: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun getMovie(id: String): Movie {
        val doc = service.getPage(id)
        val title = ItalianVixCatalog.cleanTitle(
            doc.selectFirst("h1, .movie-title, .fsubtitle h2")?.text().orEmpty(),
        )
        val tmdb = TmdbUtils.getMovie(title, language = language)
        val poster = tmdb?.poster
            ?: doc.selectFirst(".movie-poster img, img.layer-image, .poster img")?.attr("src")
                ?.let { absUrl(it) }
            ?: ""
        return Movie(
            id = id,
            title = title.ifBlank { id.substringAfterLast('/') },
            poster = poster,
            overview = tmdb?.overview
                ?: doc.selectFirst(".fdesc, .full-text, .synopsis")?.text()?.trim(),
            rating = tmdb?.rating,
            banner = tmdb?.banner,
            imdbId = tmdb?.imdbId ?: extractImdbId(doc),
            runtime = tmdb?.runtime,
            genres = tmdb?.genres ?: emptyList(),
            cast = emptyList(),
            trailer = tmdb?.trailer
                ?: doc.selectFirst("iframe[src*=youtube]")?.attr("src"),
            providerName = name,
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val doc = service.getPage(id)
        val title = ItalianVixCatalog.cleanTitle(
            doc.selectFirst("h1, .movie-title, .fsubtitle h2")?.text().orEmpty(),
        )
        val tmdb = TmdbUtils.getTvShow(title, language = language)
        val poster = tmdb?.poster
            ?: doc.selectFirst(".movie-poster img, img.layer-image, .poster img")?.attr("src")
                ?.let { absUrl(it) }
            ?: ""
        val imdb = extractImdbId(doc)
        val maxSeasons = Regex("""MAX_SEASONS\s*=\s*(\d+)""").find(doc.html())
            ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 12
        val seasons = (tmdb?.seasons?.map { it.number }?.filter { it > 0 }?.ifEmpty { null }
            ?: (1..maxSeasons).toList())
            .distinct()
            .sorted()
            .map { num ->
                Season(
                    id = "$id|$num",
                    number = num,
                    poster = tmdb?.seasons?.find { it.number == num }?.poster,
                )
            }
        return TvShow(
            id = id,
            title = title.ifBlank { id.substringAfterLast('/') },
            poster = poster,
            overview = tmdb?.overview
                ?: doc.selectFirst(".fdesc, .full-text, .synopsis")?.text()?.trim(),
            rating = tmdb?.rating,
            genres = tmdb?.genres ?: emptyList(),
            cast = emptyList(),
            seasons = seasons,
            banner = tmdb?.banner,
            imdbId = tmdb?.imdbId ?: imdb,
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
            val doc = service.getPage(showId)
            val title = ItalianVixCatalog.cleanTitle(
                doc.selectFirst("h1, .movie-title")?.text().orEmpty(),
            )
            val tmdb = TmdbUtils.getTvShow(title, language = language)
            val tmdbEpisodes = if (tmdb != null) {
                TmdbUtils.getEpisodesBySeason(tmdb.id, seasonNum, language = language)
            } else {
                emptyList()
            }
            if (tmdbEpisodes.isNotEmpty()) {
                return tmdbEpisodes.map { ep ->
                    Episode(
                        id = "$showId|$seasonNum|${ep.number}",
                        number = ep.number,
                        title = ep.title,
                        poster = ep.poster,
                        overview = ep.overview,
                    )
                }
            }
            val maxEpisodes = Regex("""MAX_EPISODES\s*=\s*(\d+)""").find(doc.html())
                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 24
            (1..maxEpisodes).map { n ->
                Episode(id = "$showId|$seasonNum|$n", number = n, title = "Episodio $n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "getEpisodesBySeason failed: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        return try {
            val url = when {
                page > 1 && id.contains("/page/") -> id.replace(Regex("/page/\\d+/"), "/page/$page/")
                page > 1 -> "${id.trimEnd('/')}/page/$page/"
                else -> id
            }
            Genre(id = id, name = "", shows = parseItems(service.getPage(url)))
        } catch (_: Exception) {
            ProviderDefaults.emptyGenre(id)
        }
    }

    override suspend fun getPeople(id: String, page: Int): People = ProviderDefaults.emptyPeople(id)

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return try {
            val showUrl = id.substringBefore("|")
            val doc = service.getPage(showUrl)
            val imdb = extractImdbId(doc) ?: return emptyList()
            val servers = mutableListOf<Video.Server>()

            when {
                id.count { it == '|' } >= 2 -> {
                    val season = id.split("|").getOrNull(1)?.toIntOrNull() ?: 1
                    val episode = id.split("|").getOrNull(2)?.toIntOrNull() ?: 1
                    servers += Video.Server(
                        id = "$VIXSRC/tv/$imdb/$season/$episode?lang=it",
                        name = "VixSrc",
                        src = "$VIXSRC/tv/$imdb/$season/$episode?lang=it",
                    )
                    servers += Video.Server(
                        id = "$VIDXGO/t/${imdb.removePrefix("tt")}/$season/$episode",
                        name = "VidxGo",
                        src = "$VIDXGO/t/${imdb.removePrefix("tt")}/$season/$episode",
                    )
                }
                videoType is Video.Type.Episode -> {
                    val season = videoType.season.number
                    val episode = videoType.number
                    servers += Video.Server(
                        id = "$VIXSRC/tv/$imdb/$season/$episode?lang=it",
                        name = "VixSrc",
                        src = "$VIXSRC/tv/$imdb/$season/$episode?lang=it",
                    )
                    servers += Video.Server(
                        id = "$VIDXGO/t/${imdb.removePrefix("tt")}/$season/$episode",
                        name = "VidxGo",
                        src = "$VIDXGO/t/${imdb.removePrefix("tt")}/$season/$episode",
                    )
                }
                else -> {
                    val iframe = doc.selectFirst("iframe[src*=vixsrc], #dle-player[src], iframe#dle-player")
                        ?.attr("src")?.takeIf { it.isNotBlank() }
                    val vix = iframe?.takeIf { it.contains("vixsrc", true) }
                        ?: "$VIXSRC/movie/$imdb?lang=it"
                    servers += Video.Server(id = vix, name = "VixSrc", src = vix)
                    servers += Video.Server(
                        id = "$VIDXGO/${imdb.removePrefix("tt")}",
                        name = "VidxGo",
                        src = "$VIDXGO/${imdb.removePrefix("tt")}",
                    )
                }
            }
            servers.distinctBy { it.src }
        } catch (e: Exception) {
            Log.e(TAG, "getServers failed: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.src.ifBlank { server.id }, server)
    }
}
