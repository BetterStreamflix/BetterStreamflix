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
 * SportsBite — PPV.st schedule/streams with locale → language/country catalog rails.
 */
object SportsBiteProvider : IptvProvider, ProviderConfigUrl {

    override val name = "SportsBite"
    override val defaultBaseUrl = "https://sportsbite.org"
    override val baseUrl: String
        get() = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            .ifBlank { defaultBaseUrl }
    override val changeUrlMutex = Mutex()
    override suspend fun onChangeUrl(forceRefresh: Boolean): String = changeUrlMutex.withLock { baseUrl }
    override val logo = "https://static.sportsbite.org/sfavicon.png"
    override val language = "en"

    private const val TAG = "SportsBite"
    private const val STREAMS_API = "https://api.ppv.st/api/streams"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val CACHE_MS = 5 * 60 * 1000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private data class StreamItem(
        val id: String,
        val name: String,
        val category: String,
        val poster: String,
        val iframe: String,
        val alwaysLive: Boolean,
        val tag: String,
        val sourceTag: String,
        val locale: String,
        val language: String,
        val country: String,
        val viewers: Int,
    )

    private var cached: List<StreamItem>? = null
    private var lastFetch = 0L

    private fun createId(item: StreamItem): String = M3uChannelIdCodec.encode(
        url = item.iframe.ifBlank { "sportsbite://${item.id}" },
        name = item.name,
        logo = item.poster,
        userAgent = USER_AGENT,
        referrer = "$baseUrl/",
        origin = baseUrl,
    )

    private fun normalizeLocale(raw: String): String =
        raw.trim().lowercase(Locale.US).replace('_', '-')

    private fun languageFromLocale(locale: String): String {
        val base = normalizeLocale(locale).substringBefore('-')
        return when (base) {
            "en" -> "en"
            "es" -> "es"
            "fr" -> "fr"
            "de" -> "de"
            "nl" -> "nl"
            "sv" -> "sv"
            "da" -> "da"
            "cs" -> "cs"
            "pt" -> "pt"
            "it" -> "it"
            "pl" -> "pl"
            "tr" -> "tr"
            "ar" -> "ar"
            else -> base.ifBlank { "en" }
        }
    }

    /** Map PPV locale codes to a browse country when the API has no explicit country field. */
    private fun countryFromLocale(locale: String): String {
        val n = normalizeLocale(locale)
        return when (n) {
            "en-gb", "en-uk" -> "uk"
            "en-au" -> "au"
            "en-ca" -> "ca"
            "en-us", "en" -> "us"
            "es-us", "es-mx" -> "mx"
            "es-es", "es" -> "es"
            "fr-fr", "fr" -> "fr"
            "fr-ca" -> "ca"
            "nl-nl", "nl" -> "nl"
            "nl-be" -> "be"
            "sv", "sv-se" -> "se"
            "da", "da-dk" -> "dk"
            "cs", "cs-cz" -> "cz"
            "de", "de-de" -> "de"
            "pt-br" -> "br"
            "pt-pt", "pt" -> "pt"
            "it", "it-it" -> "it"
            "pl", "pl-pl" -> "pl"
            "tr", "tr-tr" -> "tr"
            else -> n.substringAfter('-', "").takeIf { it.length == 2 } ?: ""
        }
    }

    private fun resolveCountry(name: String, locale: String, sourceTag: String, tag: String): String {
        LiveCatalogMeta.inferCountryFromTitle(name)?.let { return it }
        LiveCatalogMeta.inferCountryFromTitle("$name $sourceTag $tag")?.let { return it }
        return countryFromLocale(locale)
    }

    private suspend fun getAllStreams(): List<StreamItem> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cached?.takeIf { now - lastFetch < CACHE_MS }?.let { return@withContext it }
        ChannelCoverResolver.warm()
        val req = Request.Builder()
            .url(STREAMS_API)
            .header("User-Agent", USER_AGENT)
            .header("Origin", baseUrl)
            .header("Referer", "$baseUrl/")
            .header("Accept", "application/json")
            .build()
        val body = client.newCall(req).execute().use { it.body?.string().orEmpty() }
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext emptyList()
        val categories = root.optJSONArray("streams") ?: return@withContext emptyList()
        val out = ArrayList<StreamItem>()
        for (c in 0 until categories.length()) {
            val cat = categories.optJSONObject(c) ?: continue
            val catName = cat.optString("category").ifBlank { "Sports" }
            val streams = cat.optJSONArray("streams") ?: continue
            for (i in 0 until streams.length()) {
                val o = streams.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                val iframe = o.optString("iframe").trim()
                if (name.isBlank() || iframe.isBlank()) continue
                val poster = o.optString("poster").ifBlank { o.optString("image") }
                val locale = normalizeLocale(
                    o.optString("locale").ifBlank { o.optString("language") }.ifBlank { "en" },
                )
                val category = o.optString("category_name").ifBlank { catName }
                val tag = o.optString("tag")
                val sourceTag = o.optString("source_tag")
                out += StreamItem(
                    id = o.optString("uri_name").ifBlank { o.optString("id") },
                    name = name,
                    category = category,
                    poster = ChannelCoverResolver.resolveSync(name, poster, logo),
                    iframe = iframe,
                    alwaysLive = o.optInt("always_live", 0) == 1 || o.optBoolean("always_live", false),
                    tag = tag,
                    sourceTag = sourceTag,
                    locale = locale,
                    language = languageFromLocale(locale),
                    country = resolveCountry(name, locale, sourceTag, tag),
                    viewers = o.optString("viewers").toIntOrNull()
                        ?: o.optInt("viewers", 0),
                )
            }
        }
        cached = out
        lastFetch = now
        Log.d(
            TAG,
            "streams=${out.size} countries=${out.map { it.country }.filter { it.isNotBlank() }.distinct().size} " +
                "langs=${out.map { it.language }.distinct().size} cats=${out.map { it.category }.distinct().size}",
        )
        out
    }

    override suspend fun getHome(): List<Category> {
        val all = getAllStreams()
        if (all.isEmpty()) return emptyList()
        val categories = mutableListOf<Category>()
        val live247 = all.filter { it.alwaysLive }
        val events = all.filter { !it.alwaysLive }.sortedByDescending { it.viewers }

        if (events.isNotEmpty()) {
            categories += Category("🔴 Live now", events.take(40).map { toShow(it) })
            val upcoming = events.drop(40).take(24)
            if (upcoming.isNotEmpty()) {
                categories += Category("📅 Upcoming / popular", upcoming.map { toShow(it) })
            }
        }
        if (live247.isNotEmpty()) {
            categories += Category("📺 24/7 Channels", live247.take(40).map { toShow(it) })
        }
        all.groupBy { it.category }.entries
            .sortedByDescending { it.value.size }
            .forEach { (name, items) ->
                categories += Category(
                    "📂 $name (${items.size})",
                    items.sortedByDescending { it.viewers }.take(28).map { toShow(it) },
                )
            }
        categories += LiveCatalogMeta.countryCategories(
            all.filter { it.country.isNotBlank() }
                .groupBy { it.country }
                .mapValues { (_, v) -> v.map { toShow(it) } },
            limitCountries = 14,
            perCountry = 10,
        )
        categories += LiveCatalogMeta.languageCategories(
            all.groupBy { it.language }
                .mapValues { (_, v) -> v.map { toShow(it) } },
            limit = 10,
            perLang = 10,
        )
        // League / tag rails (Premier League, NFL, LaLiga, …)
        all.groupBy { it.tag.ifBlank { "Other" } }.entries
            .filter { it.key != "Other" && it.key.isNotBlank() }
            .sortedByDescending { it.value.size }
            .take(8)
            .forEach { (tag, items) ->
                categories += Category("🏷 $tag (${items.size})", items.take(20).map { toShow(it) })
            }
        return categories.filter { it.list.isNotEmpty() }.distinctBy { it.name }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        val all = getAllStreams()
        val q = query.trim()
        val results = mutableListOf<AppAdapter.Item>()
        results += LiveCatalogMeta.genreBrowseResults(
            query = q,
            countries = all.map { it.country }.filter { it.isNotBlank() }.distinct(),
            languages = all.map { it.language }.distinct(),
            categories = all.map { it.category }.distinct() +
                all.map { it.tag }.filter { it.isNotBlank() }.distinct(),
        )
        results += all.filter {
            it.name.contains(q, true) ||
                it.category.contains(q, true) ||
                it.tag.contains(q, true) ||
                it.sourceTag.contains(q, true) ||
                it.locale.contains(q, true) ||
                it.language.contains(q, true) ||
                it.country.contains(q, true) ||
                LiveCatalogMeta.countryName(it.country).contains(q, true) ||
                LiveCatalogMeta.languageName(it.language).contains(q, true)
        }.sortedByDescending { it.viewers }.take(80).map { toShow(it) }
        return results
    }

    override suspend fun getMovies(page: Int) = emptyList<Movie>()
    override suspend fun getMovie(id: String) = ProviderDefaults.emptyMovie(id, providerName = name)

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val all = getAllStreams().sortedByDescending { it.viewers }
        return LiveCatalogMeta.pageItems(all, page).map { toShow(it) }
    }

    override suspend fun getTvShow(id: String): TvShow {
        val payload = M3uChannelIdCodec.decode(id)
        val match = getAllStreams().firstOrNull { createId(it) == id }
        val cover = ChannelCoverResolver.resolveSync(payload.name, payload.logo.ifBlank { match?.poster }, logo)
        return TvShow(
            id = id,
            title = payload.name,
            poster = cover,
            banner = cover,
            overview = LiveCatalogMeta.overview(
                title = payload.name,
                country = match?.country,
                language = match?.language,
                group = match?.category,
                extra = buildString {
                    match?.tag?.takeIf { it.isNotBlank() }?.let { append(it) }
                    match?.sourceTag?.takeIf { it.isNotBlank() }?.let {
                        if (isNotEmpty()) append(" · ")
                        append(it)
                    }
                    if (match?.alwaysLive == true) {
                        if (isNotEmpty()) append(" · ")
                        append("24/7")
                    }
                }.ifBlank { "SportsBite live stream" },
            ),
            genres = buildList {
                match?.country?.takeIf { it.isNotBlank() }?.let {
                    add(Genre(LiveCatalogMeta.countryGenreId(it), LiveCatalogMeta.countryName(it)))
                }
                match?.language?.takeIf { it.isNotBlank() }?.let {
                    add(Genre(LiveCatalogMeta.languageGenreId(it), LiveCatalogMeta.languageName(it)))
                }
                match?.category?.takeIf { it.isNotBlank() }?.let {
                    add(
                        Genre(
                            LiveCatalogMeta.categoryGenreId(slugify(it)),
                            it,
                        ),
                    )
                }
                match?.tag?.takeIf { it.isNotBlank() && !it.equals(match.category, true) }?.let {
                    add(Genre(LiveCatalogMeta.categoryGenreId(slugify(it)), it))
                }
            },
            seasons = listOf(Season(id = id, number = 1, title = "Live")),
            providerName = name,
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String) =
        listOf(Episode(id = seasonId, number = 1, title = "Live"))

    override suspend fun getGenre(id: String, page: Int): Genre {
        val key = LiveCatalogMeta.parseGenreId(id) ?: return ProviderDefaults.emptyGenre(id)
        val all = getAllStreams()
        val filtered = when (key.kind) {
            LiveCatalogMeta.GenreKey.Kind.COUNTRY ->
                all.filter {
                    it.country.equals(key.value, true) ||
                        (key.value == "gb" && it.country == "uk") ||
                        (key.value == "uk" && it.country == "gb")
                }
            LiveCatalogMeta.GenreKey.Kind.LANGUAGE ->
                all.filter {
                    it.language.equals(key.value, true) ||
                        it.locale.equals(key.value, true) ||
                        it.locale.startsWith("${key.value}-")
                }
            LiveCatalogMeta.GenreKey.Kind.CATEGORY ->
                all.filter {
                    slugify(it.category) == key.value ||
                        slugify(it.tag) == key.value ||
                        it.category.equals(key.value, true) ||
                        it.tag.equals(key.value, true)
                }
            LiveCatalogMeta.GenreKey.Kind.LETTER ->
                all.filter { it.name.firstOrNull()?.uppercaseChar()?.toString() == key.value }
            else -> emptyList()
        }.sortedByDescending { it.viewers }

        val label = when (key.kind) {
            LiveCatalogMeta.GenreKey.Kind.COUNTRY ->
                "${LiveCatalogMeta.countryFlagEmoji(key.value)} ${LiveCatalogMeta.countryName(key.value)}"
            LiveCatalogMeta.GenreKey.Kind.LANGUAGE -> "🗣 ${LiveCatalogMeta.languageName(key.value)}"
            LiveCatalogMeta.GenreKey.Kind.CATEGORY ->
                "📂 ${key.value.replace('-', ' ').replaceFirstChar { it.uppercase() }}"
            LiveCatalogMeta.GenreKey.Kind.LETTER -> "🔤 ${key.value}"
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
        val iframe = payload.url
        val mirrors = buildList {
            add(iframe)
            if (iframe.contains("embedindia.st")) {
                add(iframe.replace("embedindia.st", "embed.cr7siuu.xyz"))
                add(iframe.replace("embedindia.st", "embed.ppv.st"))
            }
        }.distinct().filter { it.startsWith("http") }
        return mirrors.mapIndexed { index, url ->
            Video.Server(id = url, name = "Embed ${index + 1}", src = url)
        }.ifEmpty { listOf(Video.Server(id = id, name = "SportsBite", src = iframe)) }
    }

    override suspend fun getVideo(server: Video.Server): Video = withContext(Dispatchers.IO) {
        val embedUrl = server.src.ifBlank {
            M3uChannelIdCodec.decode(server.id).url
        }
        if (!embedUrl.startsWith("http")) {
            throw Exception("SportsBite: invalid embed URL for ${server.name}")
        }
        val html = fetchHtml(embedUrl)
            ?: throw Exception("SportsBite: could not load embed page for ${server.name} (try another server)")
        var m3u8 = LiveStreamHtmlExtractor.extractM3u8(html)
        if (m3u8.isNullOrBlank()) {
            val nested = LiveStreamHtmlExtractor.extractEmbedUrl(html)
            if (!nested.isNullOrBlank()) {
                val nestedHtml = fetchHtml(nested, referer = embedUrl)
                m3u8 = nestedHtml?.let { LiveStreamHtmlExtractor.extractM3u8(it) }
                if (!m3u8.isNullOrBlank()) {
                    return@withContext Video(
                        source = m3u8,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to nested,
                        ),
                    )
                }
            }
        }
        // Some embeds only expose SRC after the JW/Clappr bundle boots; scrape common mirrors.
        if (m3u8.isNullOrBlank()) {
            for (mirrorHost in listOf("embed.ppv.st", "embed.cr7siuu.xyz")) {
                if (!embedUrl.contains("embedindia.st")) break
                val mirrored = embedUrl.replace("embedindia.st", mirrorHost)
                val mirroredHtml = fetchHtml(mirrored, referer = "$baseUrl/") ?: continue
                m3u8 = LiveStreamHtmlExtractor.extractM3u8(mirroredHtml)
                if (!m3u8.isNullOrBlank()) {
                    return@withContext Video(
                        source = m3u8,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to mirrored,
                        ),
                    )
                }
            }
        }
        if (m3u8.isNullOrBlank()) {
            Log.e(TAG, "No m3u8 for $embedUrl (JS-gated embed)")
            throw Exception(
                "SportsBite: stream unavailable — embed is offline or blocked (try StreamSports99)",
            )
        }
        Video(
            source = m3u8,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to embedUrl,
            ),
        )
    }

    override suspend fun listLiveChannels(aroundId: String?, limit: Int) =
        getAllStreams().map {
            val slot = com.dskja.betterstreamflix.iptv.IptvProgramGuide.sportsSlot(
                alwaysLive = it.alwaysLive,
                category = it.category,
                tag = it.tag,
                viewers = it.viewers,
            )
            com.dskja.betterstreamflix.iptv.IptvLiveSession.Channel(
                id = createId(it),
                name = it.name,
                logo = it.poster,
                group = buildString {
                    append(it.category)
                    if (it.country.isNotBlank()) {
                        append(" · ")
                        append(LiveCatalogMeta.countryName(it.country))
                    }
                },
                programNow = slot.now,
                programNext = slot.next,
            )
        }.let { all ->
            com.dskja.betterstreamflix.iptv.IptvChannelWindow.fromChannels(all, aroundId, limit)
        }

    private fun fetchHtml(url: String, referer: String = "$baseUrl/"): String? = try {
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

    private fun slugify(value: String): String =
        value.trim().lowercase(Locale.US)
            .replace(Regex("""[^a-z0-9]+"""), "-")
            .trim('-')

    private fun toShow(item: StreamItem) = TvShow(
        id = createId(item),
        title = item.name,
        poster = item.poster,
        banner = item.poster,
        overview = LiveCatalogMeta.overview(
            title = item.name,
            country = item.country.takeIf { it.isNotBlank() },
            language = item.language,
            group = item.category,
            extra = item.tag.takeIf { it.isNotBlank() },
        ),
        genres = buildList {
            if (item.country.isNotBlank()) {
                add(Genre(LiveCatalogMeta.countryGenreId(item.country), LiveCatalogMeta.countryName(item.country)))
            }
            add(Genre(LiveCatalogMeta.languageGenreId(item.language), LiveCatalogMeta.languageName(item.language)))
            if (item.category.isNotBlank()) {
                add(Genre(LiveCatalogMeta.categoryGenreId(slugify(item.category)), item.category))
            }
        },
        providerName = name,
    )
}
