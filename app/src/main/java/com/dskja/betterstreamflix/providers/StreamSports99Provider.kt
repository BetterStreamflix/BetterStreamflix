package com.dskja.betterstreamflix.providers

import android.util.Log
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Genre
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.utils.ChannelCoverResolver
import com.dskja.betterstreamflix.utils.LiveCatalogMeta
import com.dskja.betterstreamflix.utils.LiveStreamHtmlExtractor
import com.dskja.betterstreamflix.utils.M3uChannelIdCodec
import com.dskja.betterstreamflix.utils.UserPreferences
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * StreamSports99 — CDN Live TV with full country sorting + sports/language browse.
 */
object StreamSports99Provider : IptvProvider, ProviderConfigUrl {

    override val name = "StreamSports99"
    override val defaultBaseUrl = "https://streamsports99.su"
    override val baseUrl: String
        get() = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            .ifBlank { defaultBaseUrl }
    override val changeUrlMutex = Mutex()
    override suspend fun onChangeUrl(forceRefresh: Boolean): String = changeUrlMutex.withLock { baseUrl }
    override val logo = "https://streamsports99.su/favicon-32x32.png"
    override val language = "en"

    private const val TAG = "StreamSports99"
    private const val CHANNELS_API =
        "https://cdnlivetv.is/api/v1/channels/?user=cdnlivetv&plan=free"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val CACHE_MS = 20 * 60 * 1000L

    private val SPORTS_KEYS = listOf(
        "sport", "espn", "sky", "bein", "nba", "nfl", "nhl", "mlb", "mls",
        "fox", "dazn", "tennis", "golf", "moto", "f1", "ufc", "boxing",
        "cricket", "rugby", "serie a", "laliga", "bundesliga", "premier",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class Channel(
        val name: String,
        val code: String,
        val playerUrl: String,
        val image: String,
        val status: String,
        val viewers: Int,
    ) {
        val country: String get() = code.ifBlank { "xx" }
        val isSports: Boolean
            get() = SPORTS_KEYS.any { name.contains(it, true) }
    }

    private var cached: List<Channel>? = null
    private var lastFetch = 0L

    private fun createId(ch: Channel): String = M3uChannelIdCodec.encode(
        url = ch.playerUrl,
        name = ch.name,
        logo = ch.image,
        userAgent = USER_AGENT,
        referrer = baseUrl,
        origin = baseUrl,
    )

    private suspend fun getAllChannels(): List<Channel> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cached?.takeIf { now - lastFetch < CACHE_MS }?.let { return@withContext it }
        ChannelCoverResolver.warm()
        val req = Request.Builder()
            .url(CHANNELS_API)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$baseUrl/")
            .header("Origin", baseUrl)
            .build()
        val body = client.newCall(req).execute().use { it.body?.string().orEmpty() }
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext emptyList()
        val arr = root.optJSONArray("channels") ?: return@withContext emptyList()
        val list = ArrayList<Channel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            val player = o.optString("url").trim()
            if (name.isBlank() || player.isBlank()) continue
            val image = o.optString("image").takeIf { it.isNotBlank() }
                ?.replace("cdnlivetv.tv", "cdnlivetv.is")
            list += Channel(
                name = name,
                code = o.optString("code").trim().lowercase(Locale.US),
                playerUrl = player.replace("cdnlivetv.tv", "cdnlivetv.is"),
                image = ChannelCoverResolver.resolveSync(name, image, logo),
                status = o.optString("status"),
                viewers = o.optInt("viewers"),
            )
        }
        val sorted = list.sortedWith(
            compareByDescending<Channel> { it.viewers }
                .thenBy { it.country }
                .thenBy { it.name.lowercase(Locale.US) },
        )
        cached = sorted
        lastFetch = now
        Log.d(TAG, "channels=${sorted.size} countries=${sorted.map { it.country }.distinct().size}")
        sorted
    }

    override suspend fun getHome(): List<Category> {
        val all = getAllChannels()
        if (all.isEmpty()) return emptyList()
        val categories = mutableListOf<Category>()
        categories += Category("🔴 Live now", all.take(40).map { toShow(it) })
        val sports = all.filter { it.isSports }
        if (sports.isNotEmpty()) {
            categories += Category("⚽ Sports (${sports.size})", sports.take(40).map { toShow(it) })
        }
        categories += LiveCatalogMeta.countryCategories(
            all.groupBy { it.country }.mapValues { (_, v) -> v.map { toShow(it) } },
            limitCountries = 20,
            perCountry = 12,
        )
        // Popular countries as dedicated rails
        listOf("us", "gb", "de", "fr", "es", "it", "tr", "br", "pl", "au").forEach { code ->
            val shows = all.filter { it.country == code }.take(24).map { toShow(it) }
            if (shows.isNotEmpty()) {
                categories += Category(
                    "${LiveCatalogMeta.countryFlagEmoji(code)} ${LiveCatalogMeta.countryName(code)}",
                    shows,
                )
            }
        }
        return categories.distinctBy { it.name }.filter { it.list.isNotEmpty() }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        val all = getAllChannels()
        val q = query.trim()
        val results = mutableListOf<AppAdapter.Item>()
        results += LiveCatalogMeta.genreBrowseResults(
            query = q,
            countries = all.map { it.country }.distinct(),
            categories = listOf("sports", "news", "entertainment"),
        )
        if ("sport".contains(q, true) || q.contains("sport", true)) {
            results += Genre(LiveCatalogMeta.categoryGenreId("sports"), "📂 Sports")
        }
        results += all.filter {
            it.name.contains(q, true) ||
                it.country.contains(q, true) ||
                LiveCatalogMeta.countryName(it.country).contains(q, true)
        }.take(80).map { toShow(it) }
        return results
    }

    override suspend fun getMovies(page: Int) = emptyList<Movie>()
    override suspend fun getMovie(id: String) = ProviderDefaults.emptyMovie(id, providerName = name)

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val all = getAllChannels()
        return LiveCatalogMeta.pageItems(all, page).map { toShow(it) }
    }

    override suspend fun getTvShow(id: String): TvShow {
        val payload = M3uChannelIdCodec.decode(id)
        val match = cached?.firstOrNull { createId(it) == id }
        val cover = ChannelCoverResolver.resolveSync(payload.name, payload.logo.ifBlank { match?.image }, logo)
        return TvShow(
            id = id,
            title = payload.name,
            poster = cover,
            banner = cover,
            overview = LiveCatalogMeta.overview(
                title = payload.name,
                country = match?.country,
                group = if (match?.isSports == true) "Sports" else "Live TV",
                extra = match?.viewers?.takeIf { it > 0 }?.let { "$it watching" },
            ),
            genres = listOfNotNull(
                match?.country?.let {
                    Genre(LiveCatalogMeta.countryGenreId(it), LiveCatalogMeta.countryName(it))
                },
                if (match?.isSports == true) {
                    Genre(LiveCatalogMeta.categoryGenreId("sports"), "Sports")
                } else null,
            ),
            seasons = listOf(Season(id = id, number = 1, title = "Live")),
            providerName = name,
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String) =
        listOf(Episode(id = seasonId, number = 1, title = "Live"))

    override suspend fun getGenre(id: String, page: Int): Genre {
        val key = LiveCatalogMeta.parseGenreId(id) ?: return ProviderDefaults.emptyGenre(id)
        val all = getAllChannels()
        val filtered = when (key.kind) {
            LiveCatalogMeta.GenreKey.Kind.COUNTRY -> all.filter {
                it.country == key.value ||
                    (key.value == "gb" && it.country == "uk") ||
                    (key.value == "uk" && it.country == "gb")
            }
            LiveCatalogMeta.GenreKey.Kind.CATEGORY -> when (key.value) {
                "sports" -> all.filter { it.isSports }
                else -> all.filter { it.name.contains(key.value, true) }
            }
            LiveCatalogMeta.GenreKey.Kind.LETTER -> all.filter {
                it.name.firstOrNull()?.uppercaseChar()?.toString() == key.value
            }
            else -> emptyList()
        }
        val label = when (key.kind) {
            LiveCatalogMeta.GenreKey.Kind.COUNTRY ->
                "${LiveCatalogMeta.countryFlagEmoji(key.value)} ${LiveCatalogMeta.countryName(key.value)}"
            LiveCatalogMeta.GenreKey.Kind.CATEGORY ->
                "📂 ${key.value.replaceFirstChar { it.uppercase() }}"
            else -> key.value
        }
        return Genre(
            id = id,
            name = "$label (${filtered.size})",
            shows = LiveCatalogMeta.pageItems(filtered, page).map { toShow(it) },
        )
    }

    override suspend fun getPeople(id: String, page: Int) = ProviderDefaults.emptyPeople(id)

    override suspend fun getServers(id: String, videoType: Video.Type) =
        listOf(Video.Server(id = id, name = "CDN Live TV"))

    override suspend fun getVideo(server: Video.Server): Video = withContext(Dispatchers.IO) {
        val payload = M3uChannelIdCodec.decode(server.id)
        val playerUrl = payload.url.ifBlank { server.src.ifBlank { server.id } }
        val html = fetchHtml(playerUrl, referer = "$baseUrl/")
            ?: throw Exception("StreamSports99: could not load player page for ${server.name}")
        val m3u8 = LiveStreamHtmlExtractor.extractM3u8(html)
        if (m3u8.isNullOrBlank()) {
            Log.e(TAG, "No m3u8 in player page")
            throw Exception("StreamSports99: no m3u8 stream found for ${server.name} (channel may be offline)")
        }
        Video(
            source = m3u8,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "https://cdnlivetv.is/",
                "Origin" to "https://cdnlivetv.is",
            ),
        )
    }

    override suspend fun listLiveChannels(aroundId: String?, limit: Int) =
        getAllChannels().map {
            com.dskja.betterstreamflix.iptv.IptvLiveSession.Channel(
                id = createId(it),
                name = it.name,
                logo = it.image,
                group = LiveCatalogMeta.countryName(it.country),
            )
        }.let { all ->
            if (aroundId == null) all.take(limit)
            else {
                val idx = all.indexOfFirst { it.id == aroundId }.coerceAtLeast(0)
                all.drop((idx - limit / 2).coerceAtLeast(0)).take(limit)
            }
        }

    private fun fetchHtml(url: String, referer: String): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Accept", "*/*")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    } catch (e: Exception) {
        Log.e(TAG, "fetchHtml ${e.message}")
        null
    }

    private fun toShow(ch: Channel) = TvShow(
        id = createId(ch),
        title = ch.name,
        poster = ch.image,
        banner = ch.image,
        overview = LiveCatalogMeta.overview(
            title = ch.name,
            country = ch.country,
            group = if (ch.isSports) "Sports" else "Live TV",
            extra = ch.viewers.takeIf { it > 0 }?.let { "$it watching" },
        ),
        genres = listOf(
            Genre(LiveCatalogMeta.countryGenreId(ch.country), LiveCatalogMeta.countryName(ch.country)),
        ),
        providerName = name,
        quality = ch.status.takeIf { it.isNotBlank() },
    )
}
