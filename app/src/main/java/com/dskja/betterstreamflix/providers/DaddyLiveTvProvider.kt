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

/**
 * DaddyLive TV — full 24/7 guide (~900 channels) with country inference + A–Z browse.
 */
object DaddyLiveTvProvider : IptvProvider, ProviderConfigUrl {

    override val name = "DaddyLive TV"
    override val defaultBaseUrl = "https://dlive.sx"
    override val baseUrl: String
        get() = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            .ifBlank { defaultBaseUrl }
    override val changeUrlMutex = Mutex()
    override suspend fun onChangeUrl(forceRefresh: Boolean): String = changeUrlMutex.withLock { baseUrl }
    override val logo = "https://dlive.sx/assets/logos/logo.png"
    override val language = "en"

    private const val TAG = "DaddyLiveTv"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val CACHE_MS = 15 * 60 * 1000L
    // Prefer paths that still embed working premiumtv/daddy.php players.
    // `watch` still points at retired daddy3.php (HTTP 404); keep it last as a fallback.
    private val PLAYERS = listOf(
        "stream" to "Player 1",
        "hub" to "Player 2",
        "casting" to "Player 3",
        "player" to "Player 4",
        "watch" to "Player 5",
        "plus" to "Player 6",
        "cast" to "Player 7",
    )
    private val SPORTS_KEYS = listOf(
        "sport", "espn", "sky sports", "bein", "nba", "nfl", "nhl", "mlb",
        "tnt sports", "dazn", "golf", "tennis", "ufc", "boxing", "f1", "motogp",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class Channel(
        val id: String,
        val name: String,
        val logo: String,
        val country: String,
        val letter: String,
    ) {
        val isSports: Boolean
            get() = SPORTS_KEYS.any { name.contains(it, true) }
    }

    private var cached: List<Channel>? = null
    private var lastFetch = 0L

    private fun createId(ch: Channel): String = M3uChannelIdCodec.encode(
        url = "$baseUrl/watch.php?id=${ch.id}",
        name = ch.name,
        logo = ch.logo,
        userAgent = USER_AGENT,
        referrer = "$baseUrl/",
        origin = baseUrl,
    )

    private fun channelNumericId(encodedId: String): String {
        val url = M3uChannelIdCodec.decode(encodedId).url
        return Regex("""[?&]id=(\d+)""").find(url)?.groupValues?.getOrNull(1)
            ?: encodedId.filter { it.isDigit() }.ifBlank { encodedId }
    }

    private suspend fun getAllChannels(): List<Channel> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cached?.takeIf { now - lastFetch < CACHE_MS }?.let { return@withContext it }
        ChannelCoverResolver.warm()
        val html = fetchHtml("$baseUrl/24-7-channels.php", referer = "$baseUrl/")
            ?: fetchHtml("$baseUrl/channels.php", referer = "$baseUrl/")
            ?: return@withContext emptyList()
        val map = LinkedHashMap<String, String>()
        Regex(
            """href="/watch\.php\?id=(\d+)"[^>]*data-title="([^"]+)"""",
            RegexOption.IGNORE_CASE,
        ).findAll(html).forEach { m ->
            val title = decodeHtml(m.groupValues[2].trim())
            if (title.isNotBlank() && !title.equals("Channel Not Listed", true)) {
                map.putIfAbsent(m.groupValues[1], title)
            }
        }
        Regex(
            """data-title="([^"]+)"[^>]*href="/watch\.php\?id=(\d+)"""",
            RegexOption.IGNORE_CASE,
        ).findAll(html).forEach { m ->
            val title = decodeHtml(m.groupValues[1].trim())
            if (title.isNotBlank()) map.putIfAbsent(m.groupValues[2], title)
        }
        Regex(
            """href="/watch\.php\?id=(\d+)"[^>]*>\s*([^<]{2,80})\s*<""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(html).forEach { m ->
            val title = decodeHtml(m.groupValues[2].trim())
            if (title.isNotBlank()) map.putIfAbsent(m.groupValues[1], title)
        }
        val imgById = HashMap<String, String>()
        Regex(
            """href="/watch\.php\?id=(\d+)"[\s\S]{0,400}?src="(https?://[^"]+\.(?:png|jpe?g|webp|svg)[^"]*)"""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(html).forEach { m ->
            imgById.putIfAbsent(m.groupValues[1], m.groupValues[2])
        }
        val list = map.map { (id, title) ->
            val country = LiveCatalogMeta.inferCountryFromTitle(title) ?: "xx"
            Channel(
                id = id,
                name = title,
                logo = ChannelCoverResolver.resolveSync(title, imgById[id], logo),
                country = country,
                letter = title.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString() ?: "#",
            )
        }.sortedBy { it.name.lowercase(Locale.US) }
        cached = list
        lastFetch = now
        Log.d(TAG, "channels=${list.size} countries=${list.map { it.country }.distinct().size}")
        list
    }

    private fun decodeHtml(raw: String): String =
        raw.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

    override suspend fun getHome(): List<Category> {
        val all = getAllChannels()
        if (all.isEmpty()) return emptyList()
        val categories = mutableListOf<Category>()
        categories += Category("📺 24/7 Channels", all.take(48).map { toShow(it) })
        val sports = all.filter { it.isSports }
        if (sports.isNotEmpty()) {
            categories += Category("⚽ Sports (${sports.size})", sports.take(40).map { toShow(it) })
        }
        categories += LiveCatalogMeta.countryCategories(
            all.filter { it.country != "xx" }
                .groupBy { it.country }
                .mapValues { (_, v) -> v.map { toShow(it) } },
            limitCountries = 18,
            perCountry = 12,
        )
        // A–Z quick rails for densest letters
        all.groupBy { it.letter }.entries
            .sortedByDescending { it.value.size }
            .take(8)
            .forEach { (letter, channels) ->
                categories += Category(
                    "🔤 $letter (${channels.size})",
                    channels.take(16).map { toShow(it) },
                )
            }
        return categories.filter { it.list.isNotEmpty() }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        val all = getAllChannels()
        val q = query.trim()
        val results = mutableListOf<AppAdapter.Item>()
        results += LiveCatalogMeta.genreBrowseResults(
            query = q,
            countries = all.map { it.country }.distinct(),
            categories = listOf("sports"),
        )
        if (q.length == 1 && q[0].isLetter()) {
            results += Genre(LiveCatalogMeta.letterGenreId(q), "🔤 ${q.uppercase(Locale.US)}")
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
        return LiveCatalogMeta.pageItems(all, page, pageSize = 60).map { toShow(it) }
    }

    override suspend fun getTvShow(id: String): TvShow {
        val payload = M3uChannelIdCodec.decode(id)
        val match = cached?.firstOrNull { createId(it) == id }
        val cover = ChannelCoverResolver.resolveSync(payload.name, payload.logo.ifBlank { match?.logo }, logo)
        return TvShow(
            id = id,
            title = payload.name,
            poster = cover,
            banner = cover,
            overview = LiveCatalogMeta.overview(
                title = payload.name,
                country = match?.country,
                group = if (match?.isSports == true) "Sports" else "24/7",
                extra = "DaddyLive TV · multi-player embeds",
            ),
            genres = listOfNotNull(
                match?.country?.takeIf { it != "xx" }?.let {
                    Genre(LiveCatalogMeta.countryGenreId(it), LiveCatalogMeta.countryName(it))
                },
                match?.letter?.let { Genre(LiveCatalogMeta.letterGenreId(it), "Letter $it") },
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
            LiveCatalogMeta.GenreKey.Kind.COUNTRY -> all.filter { it.country == key.value }
            LiveCatalogMeta.GenreKey.Kind.CATEGORY ->
                if (key.value == "sports") all.filter { it.isSports } else emptyList()
            LiveCatalogMeta.GenreKey.Kind.LETTER -> all.filter { it.letter == key.value }
            else -> emptyList()
        }
        val label = when (key.kind) {
            LiveCatalogMeta.GenreKey.Kind.COUNTRY ->
                "${LiveCatalogMeta.countryFlagEmoji(key.value)} ${LiveCatalogMeta.countryName(key.value)}"
            LiveCatalogMeta.GenreKey.Kind.LETTER -> "🔤 ${key.value}"
            LiveCatalogMeta.GenreKey.Kind.CATEGORY -> "📂 ${key.value.replaceFirstChar { it.uppercase() }}"
            else -> key.value
        }
        return Genre(
            id = id,
            name = "$label (${filtered.size})",
            shows = LiveCatalogMeta.pageItems(filtered, page, pageSize = 60).map { toShow(it) },
        )
    }

    override suspend fun getPeople(id: String, page: Int) = ProviderDefaults.emptyPeople(id)

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val channelId = channelNumericId(id)
        return PLAYERS.map { (path, label) ->
            Video.Server(
                id = "$baseUrl/$path/stream-$channelId.php",
                name = label,
                src = "$baseUrl/$path/stream-$channelId.php",
            )
        }
    }

    override suspend fun getVideo(server: Video.Server): Video = withContext(Dispatchers.IO) {
        val pageUrl = server.src.ifBlank { server.id }
        val watchReferer = pageUrl
            .replace(Regex("""/(plus|watch|stream|cast|player|casting|hub)/stream-(\d+)\.php"""), "/watch.php?id=$2")
            .ifBlank { "$baseUrl/" }
        val pageHtml = fetchHtml(pageUrl, referer = watchReferer)
            ?: throw Exception("DaddyLive TV: could not load ${server.name} page (try another server)")
        LiveStreamHtmlExtractor.extractM3u8(pageHtml)?.let {
            return@withContext Video(
                source = it,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to pageUrl),
            )
        }
        val embedUrl = LiveStreamHtmlExtractor.extractEmbedUrl(pageHtml)
            ?: Regex("""https?://[a-zA-Z0-9.-]+/(?:embed|premiumtv)/[a-zA-Z0-9_./?-]+""")
                .find(pageHtml)?.value
        if (embedUrl.isNullOrBlank()) {
            Log.e(TAG, "No embed on $pageUrl")
            throw Exception("DaddyLive TV: no embed found for ${server.name} (try another server)")
        }
        val candidates = LiveStreamHtmlExtractor.normalizeDaddyLiveEmbed(embedUrl)
        var lastError: String? = null
        for (candidate in candidates) {
            val embedHtml = fetchHtml(candidate, referer = pageUrl)
            if (embedHtml.isNullOrBlank()) {
                lastError = "embed HTTP miss for $candidate"
                continue
            }
            // Retired daddyN.php hosts return a tiny nginx 404 body.
            if (embedHtml.contains("<title>404 Not Found</title>", ignoreCase = true) ||
                (embedHtml.length < 800 && embedHtml.contains("404 Not Found", ignoreCase = true))
            ) {
                lastError = "embed 404 for $candidate"
                continue
            }
            val m3u8 = LiveStreamHtmlExtractor.extractM3u8(embedHtml)
            if (m3u8.isNullOrBlank()) {
                lastError = "no m3u8 in $candidate"
                continue
            }
            val embedOrigin = Regex("""^(https?://[^/]+)""").find(candidate)?.groupValues?.getOrNull(1)
                ?: baseUrl
            return@withContext Video(
                source = m3u8,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to candidate,
                    "Origin" to embedOrigin,
                ),
            )
        }
        Log.e(TAG, "DaddyLive resolve failed for $embedUrl ($lastError)")
        throw Exception(
            "DaddyLive TV: stream unavailable for ${server.name} (try another server)",
        )
    }

    override suspend fun listLiveChannels(aroundId: String?, limit: Int) =
        getAllChannels().map {
            com.dskja.betterstreamflix.iptv.IptvLiveSession.Channel(
                id = createId(it),
                name = it.name,
                logo = it.logo,
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
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()?.toString(Charsets.ISO_8859_1)
        }
    } catch (e: Exception) {
        Log.e(TAG, "fetchHtml ${e.message}")
        null
    }

    private fun toShow(ch: Channel) = TvShow(
        id = createId(ch),
        title = ch.name,
        poster = ch.logo,
        banner = ch.logo,
        overview = LiveCatalogMeta.overview(
            title = ch.name,
            country = ch.country.takeIf { it != "xx" },
            group = if (ch.isSports) "Sports" else "24/7",
        ),
        genres = listOfNotNull(
            ch.country.takeIf { it != "xx" }?.let {
                Genre(LiveCatalogMeta.countryGenreId(it), LiveCatalogMeta.countryName(it))
            },
        ),
        providerName = name,
    )
}
