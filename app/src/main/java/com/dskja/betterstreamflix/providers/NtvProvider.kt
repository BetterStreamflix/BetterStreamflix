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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * NTV / NTVSTREAM — live matches by sport + 24/7 channels by server/country.
 */
object NtvProvider : IptvProvider, ProviderConfigUrl {

    override val name = "NTV"
    override val defaultBaseUrl = "https://ntv.cx"
    override val baseUrl: String
        get() = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            .ifBlank { defaultBaseUrl }
    override val changeUrlMutex = Mutex()
    override suspend fun onChangeUrl(forceRefresh: Boolean): String = changeUrlMutex.withLock { baseUrl }
    override val logo = "https://ntv.cx/assets/img/logo1.png"
    override val language = "en"

    private const val TAG = "NtvProvider"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val CACHE_MS = 10 * 60 * 1000L
    private val SERVERS = listOf("kobra", "falcon", "raptor", "phoenix", "titan")

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private data class Item(
        val id: String,
        val title: String,
        val poster: String,
        val kind: Kind,
        val server: String = "",
        val playerUrl: String = "",
        val category: String = "",
        val country: String = "",
        val live: Boolean = false,
    ) {
        enum class Kind { CHANNEL, MATCH }
    }

    private var cachedChannels: List<Item>? = null
    private var cachedMatches: List<Item>? = null
    private var lastFetch = 0L

    private fun createId(item: Item): String = M3uChannelIdCodec.encode(
        url = when (item.kind) {
            Item.Kind.CHANNEL -> item.playerUrl.ifBlank { "ntv-channel://${item.id}" }
            Item.Kind.MATCH -> "$baseUrl/watch/${item.server}/${item.id}"
        },
        name = item.title,
        logo = item.poster,
        userAgent = USER_AGENT,
        referrer = "$baseUrl/",
        origin = baseUrl,
    )

    private suspend fun refreshCatalog(): Pair<List<Item>, List<Item>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val channelsSnap = cachedChannels
        val matchesSnap = cachedMatches
        if (channelsSnap != null && matchesSnap != null && now - lastFetch < CACHE_MS) {
            return@withContext channelsSnap to matchesSnap
        }
        ChannelCoverResolver.warm()
        val channels = loadChannels()
        val matches = loadMatches()
        cachedChannels = channels
        cachedMatches = matches
        lastFetch = now
        channels to matches
    }

    private fun loadChannels(): List<Item> {
        val json = fetchJson("$baseUrl/api/get-channels") ?: return emptyList()
        val arr = json.optJSONArray("channels") ?: return emptyList()
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("channel_name").trim()
            if (title.isBlank()) continue
            val player = o.optString("channel_url").replace("cdnlivetv.tv", "cdnlivetv.is")
            val rawPoster = o.optString("channel_image").ifBlank {
                o.optString("image").ifBlank { o.optString("icon") }
            }
            val code = o.optString("channel_code").trim().lowercase(Locale.US)
            val country = code.ifBlank {
                LiveCatalogMeta.inferCountryFromTitle(title) ?: ""
            }
            out += Item(
                id = o.optString("channel_id").ifBlank { title },
                title = title,
                poster = ChannelCoverResolver.resolveSync(title, rawPoster, logo),
                kind = Item.Kind.CHANNEL,
                server = o.optString("server"),
                playerUrl = player,
                category = o.optString("server").ifBlank { "Channels" },
                country = country,
            )
        }
        return out
    }

    private fun loadMatches(): List<Item> {
        val out = LinkedHashMap<String, Item>()
        for (server in SERVERS) {
            val json = fetchJson("$baseUrl/api/get-matches?server=$server&type=both") ?: continue
            listOf("live", "upcoming").forEach { key ->
                val arr = json.optJSONArray(key) ?: return@forEach
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id").trim()
                    if (id.isBlank()) continue
                    val title = o.optString("title").trim()
                    if (title.isBlank()) continue
                    var poster = o.optString("poster")
                    if (poster.startsWith("/")) poster = baseUrl + poster
                    out.putIfAbsent(
                        id,
                        Item(
                            id = id,
                            title = title,
                            poster = ChannelCoverResolver.resolveSync(title, poster, logo),
                            kind = Item.Kind.MATCH,
                            server = server,
                            category = o.optString("category").ifBlank { o.optString("tournament") },
                            live = o.optBoolean("live") || key == "live",
                        ),
                    )
                }
            }
        }
        return out.values.toList()
    }

    override suspend fun getHome(): List<Category> {
        val (channels, matches) = refreshCatalog()
        val categories = mutableListOf<Category>()
        val live = matches.filter { it.live }.ifEmpty { matches }
        if (live.isNotEmpty()) {
            categories += Category("🔴 Live & Upcoming", live.take(40).map { toShow(it) })
        }
        matches.groupBy { it.category.ifBlank { "Sports" } }.entries
            .sortedByDescending { it.value.size }
            .forEach { (cat, items) ->
                val label = cat.replace('-', ' ').replaceFirstChar { it.uppercase() }
                categories += Category("📂 $label (${items.size})", items.take(28).map { toShow(it) })
            }
        if (channels.isNotEmpty()) {
            categories += Category("📺 24/7 Channels", channels.take(40).map { toShow(it) })
        }
        channels.groupBy { it.server.ifBlank { "Channels" } }.forEach { (server, items) ->
            categories += Category(
                "🖥 ${server.replaceFirstChar { it.uppercase() }} (${items.size})",
                items.take(24).map { toShow(it) },
            )
        }
        categories += LiveCatalogMeta.countryCategories(
            channels.filter { it.country.isNotBlank() }
                .groupBy { it.country }
                .mapValues { (_, v) -> v.map { toShow(it) } },
            limitCountries = 12,
            perCountry = 10,
        )
        return categories.filter { it.list.isNotEmpty() }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        val (channels, matches) = refreshCatalog()
        val all = matches + channels
        val q = query.trim()
        val results = mutableListOf<AppAdapter.Item>()
        results += LiveCatalogMeta.genreBrowseResults(
            query = q,
            countries = channels.map { it.country }.filter { it.isNotBlank() }.distinct(),
            categories = matches.map { it.category }.filter { it.isNotBlank() }.distinct() +
                channels.map { it.server }.filter { it.isNotBlank() }.distinct(),
        )
        results += all.filter {
            it.title.contains(q, true) ||
                it.category.contains(q, true) ||
                it.server.contains(q, true) ||
                it.country.contains(q, true) ||
                LiveCatalogMeta.countryName(it.country).contains(q, true)
        }.distinctBy { it.id }.take(80).map { toShow(it) }
        return results
    }

    override suspend fun getMovies(page: Int) = emptyList<Movie>()
    override suspend fun getMovie(id: String) = ProviderDefaults.emptyMovie(id, providerName = name)

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val (channels, matches) = refreshCatalog()
        val all = matches + channels
        val size = 50
        val start = (page - 1) * size
        if (start >= all.size) return emptyList()
        return all.drop(start).take(size).map { toShow(it) }
    }

    override suspend fun getTvShow(id: String): TvShow {
        val payload = M3uChannelIdCodec.decode(id)
        val (channels, matches) = refreshCatalog()
        val match = (matches + channels).firstOrNull { createId(it) == id }
        val cover = ChannelCoverResolver.resolveSync(payload.name, payload.logo.ifBlank { match?.poster }, logo)
        return TvShow(
            id = id,
            title = payload.name,
            poster = cover,
            banner = cover,
            overview = LiveCatalogMeta.overview(
                title = payload.name,
                country = match?.country,
                group = match?.category?.ifBlank { match.server },
                extra = when (match?.kind) {
                    Item.Kind.MATCH -> if (match.live) "Live match" else "Upcoming / sports"
                    Item.Kind.CHANNEL -> "NTVSTREAM 24/7"
                    null -> "NTVSTREAM live"
                },
            ),
            genres = listOfNotNull(
                match?.category?.takeIf { it.isNotBlank() }?.let {
                    Genre(LiveCatalogMeta.categoryGenreId(it), it.replace('-', ' ').replaceFirstChar { c -> c.uppercase() })
                },
                match?.country?.takeIf { it.isNotBlank() }?.let {
                    Genre(LiveCatalogMeta.countryGenreId(it), LiveCatalogMeta.countryName(it))
                },
                match?.server?.takeIf { it.isNotBlank() }?.let {
                    Genre(LiveCatalogMeta.serverGenreId(it), it.replaceFirstChar { c -> c.uppercase() })
                },
            ),
            seasons = listOf(Season(id = id, number = 1, title = "Live")),
            providerName = name,
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String) =
        listOf(Episode(id = seasonId, number = 1, title = "Live"))

    override suspend fun getGenre(id: String, page: Int): Genre {
        val key = LiveCatalogMeta.parseGenreId(id) ?: return ProviderDefaults.emptyGenre(id)
        val (channels, matches) = refreshCatalog()
        val filtered = when (key.kind) {
            LiveCatalogMeta.GenreKey.Kind.CATEGORY ->
                (matches + channels).filter {
                    it.category.equals(key.value, true) || it.server.equals(key.value, true)
                }
            LiveCatalogMeta.GenreKey.Kind.COUNTRY ->
                channels.filter { it.country.equals(key.value, true) }
            LiveCatalogMeta.GenreKey.Kind.SERVER ->
                channels.filter { it.server.equals(key.value, true) }
            else -> emptyList()
        }
        val label = when (key.kind) {
            LiveCatalogMeta.GenreKey.Kind.COUNTRY ->
                "${LiveCatalogMeta.countryFlagEmoji(key.value)} ${LiveCatalogMeta.countryName(key.value)}"
            LiveCatalogMeta.GenreKey.Kind.CATEGORY ->
                "📂 ${key.value.replace('-', ' ').replaceFirstChar { it.uppercase() }}"
            LiveCatalogMeta.GenreKey.Kind.SERVER ->
                "🖥 ${key.value.replaceFirstChar { it.uppercase() }}"
            else -> key.value
        }
        return Genre(
            id = id,
            name = "$label (${filtered.size})",
            shows = LiveCatalogMeta.pageItems(filtered, page).map { toShow(it) },
        )
    }
    override suspend fun getPeople(id: String, page: Int) = ProviderDefaults.emptyPeople(id)

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val payload = M3uChannelIdCodec.decode(id)
        val url = payload.url
        return when {
            url.contains("/watch/") -> {
                val parts = url.removePrefix(baseUrl).trim('/').split('/')
                // watch / server / matchId
                val server = parts.getOrNull(1) ?: "kobra"
                val matchId = parts.getOrNull(2) ?: return listOf(Video.Server(id, "NTV"))
                listOf(0, 1, 2, 3).map { idx ->
                    Video.Server(
                        id = "$baseUrl/api/get-watch-streams?server=$server&match=$matchId&source=$idx",
                        name = "Source ${idx + 1}",
                        src = "$baseUrl/api/get-watch-streams?server=$server&match=$matchId&source=$idx",
                    )
                }
            }
            url.contains("cdnlivetv") || url.contains("/channels/player/") ->
                listOf(Video.Server(id = id, name = "CDN Live", src = url))
            url.startsWith("ntv-channel://") -> {
                // DLHD-backed channel without direct player URL — open Daddy-style watch if numeric
                val raw = url.removePrefix("ntv-channel://")
                listOf(Video.Server(id = id, name = "NTV Channel", src = raw))
            }
            else -> listOf(Video.Server(id = id, name = "NTV", src = url))
        }
    }

    override suspend fun getVideo(server: Video.Server): Video = withContext(Dispatchers.IO) {
        val src = server.src.ifBlank { server.id }
        val video = when {
            src.contains("get-watch-streams") -> resolveWatchStreams(src)
            src.contains("cdnlivetv") || src.contains("/channels/player/") -> resolvePlayerPage(src)
            else -> {
                val payload = M3uChannelIdCodec.decode(server.id)
                when {
                    payload.url.contains("cdnlivetv") || payload.url.contains("/channels/player/") ->
                        resolvePlayerPage(payload.url)
                    payload.url.contains("/watch/") -> {
                        val parts = payload.url.removePrefix(baseUrl).trim('/').split('/')
                        val api =
                            "$baseUrl/api/get-watch-streams?server=${parts.getOrNull(1)}&match=${parts.getOrNull(2)}&source=0"
                        resolveWatchStreams(api)
                    }
                    else -> Video("")
                }
            }
        }
        if (video.source.isBlank()) {
            throw Exception("NTV: no stream found for ${server.name} (try another source)")
        }
        video
    }

    private fun resolveWatchStreams(apiUrl: String): Video {
        val json = fetchJson(apiUrl) ?: return Video("")
        if (!json.optBoolean("success", false)) {
            Log.w(TAG, "watch streams busy/fail: ${json.optString("error")}")
            return Video("")
        }
        val embeds = mutableListOf<String>()
        json.optString("embedUrl").takeIf { it.isNotBlank() }?.let { embeds += it }
        val streams = json.optJSONArray("streams")
        if (streams != null) {
            for (i in 0 until streams.length()) {
                streams.optJSONObject(i)?.optString("embedUrl")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { embeds += it }
            }
        }
        for (embed in embeds.distinct()) {
            val absolute = if (embed.startsWith("http")) embed else baseUrl + embed
            resolvePlayerPage(absolute).takeIf { it.source.isNotBlank() }?.let { return it }
            val html = fetchHtml(absolute) ?: continue
            LiveStreamHtmlExtractor.extractM3u8(html)?.let { m3u8 ->
                return Video(
                    source = m3u8,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to absolute,
                    ),
                )
            }
            val nested = LiveStreamHtmlExtractor.extractEmbedUrl(html)
            if (!nested.isNullOrBlank()) {
                resolvePlayerPage(nested).takeIf { it.source.isNotBlank() }?.let { return it }
            }
        }
        return Video("")
    }

    private fun resolvePlayerPage(playerUrl: String): Video {
        val html = fetchHtml(playerUrl) ?: return Video("")
        val m3u8 = LiveStreamHtmlExtractor.extractM3u8(html) ?: return Video("")
        val origin = Regex("""^(https?://[^/]+)""").find(playerUrl)?.groupValues?.getOrNull(1)
            ?: "https://cdnlivetv.is"
        return Video(
            source = m3u8,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to playerUrl,
                "Origin" to origin,
            ),
        )
    }

    override suspend fun listLiveChannels(aroundId: String?, limit: Int): List<com.dskja.betterstreamflix.iptv.IptvLiveSession.Channel> {
        val (channels, matches) = refreshCatalog()
        val all = (matches + channels).map {
            com.dskja.betterstreamflix.iptv.IptvLiveSession.Channel(
                id = createId(it),
                name = it.title,
                logo = it.poster,
                group = it.category,
            )
        }
        if (aroundId == null) return all.take(limit)
        val idx = all.indexOfFirst { it.id == aroundId }.coerceAtLeast(0)
        return all.drop((idx - limit / 2).coerceAtLeast(0)).take(limit)
    }

    private fun fetchJson(url: String): JSONObject? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$baseUrl/")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            JSONObject(resp.body?.string().orEmpty())
        }
    } catch (e: Exception) {
        Log.e(TAG, "fetchJson ${e.message}")
        null
    }

    private fun fetchHtml(url: String): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$baseUrl/")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    } catch (_: Exception) {
        null
    }

    private fun toShow(item: Item) = TvShow(
        id = createId(item),
        title = item.title,
        poster = item.poster,
        banner = item.poster,
        overview = LiveCatalogMeta.overview(
            title = item.title,
            country = item.country.takeIf { it.isNotBlank() },
            group = item.category.ifBlank { item.server },
            extra = if (item.live) "LIVE" else null,
        ),
        genres = listOfNotNull(
            item.category.takeIf { it.isNotBlank() }?.let {
                Genre(
                    LiveCatalogMeta.categoryGenreId(it),
                    it.replace('-', ' ').replaceFirstChar { c -> c.uppercase() },
                )
            },
            item.country.takeIf { it.isNotBlank() }?.let {
                Genre(LiveCatalogMeta.countryGenreId(it), LiveCatalogMeta.countryName(it))
            },
        ),
        providerName = name,
    )
}
