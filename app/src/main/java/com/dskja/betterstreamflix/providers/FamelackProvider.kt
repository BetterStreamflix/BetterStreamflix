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
import com.dskja.betterstreamflix.utils.M3uChannelIdCodec
import com.dskja.betterstreamflix.utils.UserPreferences
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * Famelack — full live TV directory from famelack-data (countries + categories + languages).
 */
object FamelackProvider : IptvProvider, ProviderConfigUrl {

    override val name = "Famelack"
    override val defaultBaseUrl = "https://raw.githubusercontent.com/Famelack/famelack-data/main"
    override val baseUrl: String
        get() = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL)
            .ifBlank { defaultBaseUrl }
    override val changeUrlMutex = Mutex()
    override suspend fun onChangeUrl(forceRefresh: Boolean): String = changeUrlMutex.withLock { baseUrl }
    override val logo = "https://famelack.com/favicon.ico"
    override val language = "en"

    private const val TAG = "FamelackProvider"
    private const val CACHE_MS = 45 * 60 * 1000L
    private val HOME_CATEGORIES = listOf(
        "sports", "news", "entertainment", "movies", "kids", "music",
        "documentary", "series", "lifestyle", "comedy", "culture",
    )
    private val HOME_COUNTRIES = LiveCatalogMeta.PRIORITY_COUNTRIES

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    data class Channel(
        val id: String,
        val name: String,
        val url: String,
        val logo: String,
        val group: String,
        val country: String,
        val languages: List<String>,
    )

    private var cached: List<Channel>? = null
    private var knownCountries: List<String> = HOME_COUNTRIES
    private var knownCategories: List<String> = HOME_CATEGORIES
    private var lastFetch = 0L
    private val packCache = mutableMapOf<String, List<Channel>>()

    private fun createId(ch: Channel): String = M3uChannelIdCodec.encode(
        url = ch.url,
        name = ch.name,
        logo = ch.logo,
        referrer = "https://famelack.com/",
        origin = "https://famelack.com",
    )

    private suspend fun refreshCatalog(): List<Channel> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cached?.takeIf { now - lastFetch < CACHE_MS }?.let { return@withContext it }
        ChannelCoverResolver.warm()
        refreshIndex()
        val out = LinkedHashMap<String, Channel>()
        coroutineScope {
            val jobs = buildList {
                HOME_CATEGORIES.forEach { slug ->
                    add(async { loadCategory(slug) })
                }
                HOME_COUNTRIES.forEach { code ->
                    add(async { loadCountry(code) })
                }
            }
            jobs.awaitAll().forEach { list ->
                list.forEach { out.putIfAbsent(it.id, it) }
            }
        }
        val list = out.values.sortedBy { it.name.lowercase(Locale.US) }
        cached = list
        lastFetch = now
        Log.d(TAG, "catalog=${list.size} countries=${knownCountries.size} cats=${knownCategories.size}")
        list
    }

    private fun refreshIndex() {
        knownCountries = fetchGithubNames("tv/compressed/countries")
            .map { it.removeSuffix(".json").lowercase(Locale.US) }
            .ifEmpty { HOME_COUNTRIES }
        knownCategories = fetchGithubNames("tv/compressed/categories")
            .map { it.removeSuffix(".json").lowercase(Locale.US) }
            .filter { it != "all" }
            .ifEmpty { HOME_CATEGORIES }
    }

    private fun fetchGithubNames(path: String): List<String> {
        return try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/Famelack/famelack-data/contents/$path")
                .header("User-Agent", "BetterStreamflix")
                .header("Accept", "application/vnd.github+json")
                .build()
            val body = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            val arr = JSONArray(body)
            buildList {
                for (i in 0 until arr.length()) {
                    val name = arr.optJSONObject(i)?.optString("name").orEmpty()
                    if (name.endsWith(".json")) add(name)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "index $path: ${e.message}")
            emptyList()
        }
    }

    private fun loadCategory(slug: String): List<Channel> {
        val key = "cat:$slug"
        packCache[key]?.let { return it }
        val url = "$baseUrl/tv/compressed/categories/$slug.json"
        val list = parseChannelArray(fetchGzipJson(url), groupFallback = slug, countryFallback = "")
        packCache[key] = list
        return list
    }

    private fun loadCountry(code: String): List<Channel> {
        val normalized = code.lowercase(Locale.US).let { if (it == "gb") "uk" else it }
        val key = "country:$normalized"
        packCache[key]?.let { return it }
        val url = "$baseUrl/tv/compressed/countries/$normalized.json"
        val list = parseChannelArray(
            fetchGzipJson(url),
            groupFallback = "general",
            countryFallback = normalized,
        )
        packCache[key] = list
        return list
    }

    private fun ensurePack(genre: LiveCatalogMeta.GenreKey): List<Channel> {
        return when (genre.kind) {
            LiveCatalogMeta.GenreKey.Kind.COUNTRY -> loadCountry(genre.value)
            LiveCatalogMeta.GenreKey.Kind.CATEGORY -> loadCategory(genre.value)
            LiveCatalogMeta.GenreKey.Kind.LANGUAGE -> {
                // Languages are derived from already-loaded packs.
                emptyList()
            }
            else -> emptyList()
        }
    }

    private fun fetchGzipJson(url: String): JSONArray? {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", "BetterStreamflix").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                val text = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                    GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader().readText()
                } else {
                    bytes.toString(Charsets.UTF_8)
                }
                JSONArray(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetch $url: ${e.message}")
            null
        }
    }

    private fun parseChannelArray(
        arr: JSONArray?,
        groupFallback: String,
        countryFallback: String,
    ): List<Channel> {
        if (arr == null) return emptyList()
        val out = ArrayList<Channel>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim()
            if (name.isBlank()) continue
            val streams = obj.optJSONObject("sources")?.optJSONArray("streams") ?: continue
            var streamUrl = ""
            for (j in 0 until streams.length()) {
                val candidate = streams.optString(j)
                if (candidate.contains(".m3u8", true) || candidate.startsWith("http")) {
                    streamUrl = candidate
                    break
                }
            }
            if (streamUrl.isBlank()) continue
            val id = obj.optString("nanoid").ifBlank { streamUrl }
            val country = obj.optString("country").ifBlank { countryFallback }.lowercase(Locale.US)
            val langs = buildList {
                val arrLang = obj.optJSONArray("languages")
                if (arrLang != null) {
                    for (k in 0 until arrLang.length()) {
                        val lang = arrLang.optString(k).trim()
                        if (lang.isNotBlank()) add(lang.lowercase(Locale.US))
                    }
                }
            }
            val group = obj.optString("category").ifBlank { groupFallback }.lowercase(Locale.US)
            val rawLogo = obj.optString("logo").takeIf { it.isNotBlank() }
                ?: obj.optString("image").takeIf { it.isNotBlank() }
            out += Channel(
                id = id,
                name = name,
                url = streamUrl,
                logo = ChannelCoverResolver.resolveSync(name, rawLogo, logo),
                group = group,
                country = country,
                languages = langs,
            )
        }
        return out
    }

    private fun allKnown(): List<Channel> = cached ?: emptyList()

    private fun mergeWithPack(extra: List<Channel>): List<Channel> {
        val map = LinkedHashMap<String, Channel>()
        allKnown().forEach { map[it.id] = it }
        extra.forEach { map.putIfAbsent(it.id, it) }
        return map.values.toList()
    }

    override suspend fun getHome(): List<Category> {
        val all = refreshCatalog()
        if (all.isEmpty()) return emptyList()
        val categories = mutableListOf<Category>()
        categories += Category(
            "⭐ Featured",
            all.filter { it.group == "sports" || it.group == "news" }
                .distinctBy { it.name }.take(36).map { toShow(it) },
        )
        HOME_CATEGORIES.forEach { slug ->
            val shows = all.filter { it.group.equals(slug, true) }
                .distinctBy { it.name }.take(28).map { toShow(it) }
            if (shows.isNotEmpty()) {
                categories += Category(
                    "📂 ${slug.replaceFirstChar { it.uppercase() }} (${shows.size}+)",
                    shows,
                )
            }
        }
        categories += LiveCatalogMeta.countryCategories(
            all.groupBy { it.country }.mapValues { (_, v) -> v.distinctBy { it.name }.map { toShow(it) } },
            limitCountries = 16,
            perCountry = 10,
        )
        val byLang = mutableMapOf<String, MutableList<TvShow>>()
        all.forEach { ch ->
            ch.languages.forEach { lang ->
                byLang.getOrPut(lang) { mutableListOf() }.add(toShow(ch))
            }
        }
        categories += LiveCatalogMeta.languageCategories(
            byLang.mapValues { (_, v) -> v.distinctBy { it.id } },
            limit = 10,
            perLang = 10,
        )
        return categories.filter { it.list.isNotEmpty() }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        val all = refreshCatalog()
        val q = query.trim()
        val results = mutableListOf<AppAdapter.Item>()
        results += LiveCatalogMeta.genreBrowseResults(
            query = q,
            countries = knownCountries,
            languages = all.flatMap { it.languages }.distinct(),
            categories = knownCategories,
        )
        results += all.filter {
            it.name.contains(q, true) ||
                it.country.contains(q, true) ||
                LiveCatalogMeta.countryName(it.country).contains(q, true) ||
                it.group.contains(q, true) ||
                it.languages.any { lang ->
                    lang.contains(q, true) || LiveCatalogMeta.languageName(lang).contains(q, true)
                }
        }.distinctBy { it.name }.take(80).map { toShow(it) }
        return results
    }

    override suspend fun getMovies(page: Int) = emptyList<Movie>()
    override suspend fun getMovie(id: String) = ProviderDefaults.emptyMovie(id, providerName = name)

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val all = refreshCatalog()
        return LiveCatalogMeta.pageItems(all, page).map { toShow(it) }
    }

    override suspend fun getTvShow(id: String): TvShow {
        val payload = M3uChannelIdCodec.decode(id)
        val match = allKnown().firstOrNull { createId(it) == id }
            ?: packCache.values.flatten().firstOrNull { createId(it) == id }
        val cover = ChannelCoverResolver.resolveSync(payload.name, payload.logo.ifBlank { match?.logo }, logo)
        return TvShow(
            id = id,
            title = payload.name,
            poster = cover,
            banner = cover,
            overview = LiveCatalogMeta.overview(
                title = payload.name,
                country = match?.country,
                language = match?.languages?.firstOrNull(),
                group = match?.group,
                extra = "Live TV via Famelack",
            ),
            genres = buildList {
                match?.country?.takeIf { it.isNotBlank() }?.let {
                    add(Genre(LiveCatalogMeta.countryGenreId(it), LiveCatalogMeta.countryName(it)))
                }
                match?.languages?.forEach { lang ->
                    add(Genre(LiveCatalogMeta.languageGenreId(lang), LiveCatalogMeta.languageName(lang)))
                }
                match?.group?.takeIf { it.isNotBlank() }?.let {
                    add(Genre(LiveCatalogMeta.categoryGenreId(it), it.replaceFirstChar { c -> c.uppercase() }))
                }
            },
            seasons = listOf(Season(id = id, number = 1, title = "Live")),
            providerName = name,
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String) =
        listOf(Episode(id = seasonId, number = 1, title = "Live"))

    override suspend fun getGenre(id: String, page: Int): Genre = withContext(Dispatchers.IO) {
        val key = LiveCatalogMeta.parseGenreId(id)
            ?: return@withContext ProviderDefaults.emptyGenre(id)
        ChannelCoverResolver.warm()
        val channels = when (key.kind) {
            LiveCatalogMeta.GenreKey.Kind.COUNTRY -> {
                val pack = ensurePack(key)
                mergeWithPack(pack).filter { it.country.equals(key.value, true) ||
                    (key.value == "gb" && it.country == "uk") ||
                    (key.value == "uk" && it.country == "gb") }
            }
            LiveCatalogMeta.GenreKey.Kind.CATEGORY -> {
                val pack = ensurePack(key)
                mergeWithPack(pack).filter { it.group.equals(key.value, true) }
            }
            LiveCatalogMeta.GenreKey.Kind.LANGUAGE -> {
                refreshCatalog()
                allKnown().filter { ch -> ch.languages.any { it.equals(key.value, true) } }
            }
            LiveCatalogMeta.GenreKey.Kind.LETTER -> {
                refreshCatalog()
                allKnown().filter { it.name.firstOrNull()?.uppercaseChar()?.toString() == key.value }
            }
            LiveCatalogMeta.GenreKey.Kind.SERVER -> emptyList()
        }.distinctBy { it.name }.sortedBy { it.name.lowercase(Locale.US) }

        val label = when (key.kind) {
            LiveCatalogMeta.GenreKey.Kind.COUNTRY ->
                "${LiveCatalogMeta.countryFlagEmoji(key.value)} ${LiveCatalogMeta.countryName(key.value)}"
            LiveCatalogMeta.GenreKey.Kind.LANGUAGE -> "🗣 ${LiveCatalogMeta.languageName(key.value)}"
            LiveCatalogMeta.GenreKey.Kind.CATEGORY ->
                "📂 ${key.value.replace('-', ' ').replaceFirstChar { it.uppercase() }}"
            LiveCatalogMeta.GenreKey.Kind.LETTER -> "🔤 ${key.value}"
            else -> key.value
        }
        Genre(
            id = id,
            name = "$label (${channels.size})",
            shows = LiveCatalogMeta.pageItems(channels, page).map { toShow(it) },
        )
    }

    override suspend fun getPeople(id: String, page: Int) = ProviderDefaults.emptyPeople(id)

    override suspend fun getServers(id: String, videoType: Video.Type) =
        listOf(Video.Server(id = id, name = "Famelack HLS"))

    override suspend fun getVideo(server: Video.Server): Video {
        val payload = M3uChannelIdCodec.decode(server.id)
        if (payload.url.isBlank()) {
            throw Exception("Famelack: no stream URL found for ${server.name}")
        }
        return Video(
            source = payload.url,
            headers = M3uChannelIdCodec.playbackHeaders(server.id).ifEmpty {
                mapOf(
                    "User-Agent" to "Mozilla/5.0",
                    "Referer" to "https://famelack.com/",
                )
            },
        )
    }

    override suspend fun listLiveChannels(aroundId: String?, limit: Int) =
        refreshCatalog().map {
            com.dskja.betterstreamflix.iptv.IptvLiveSession.Channel(
                id = createId(it),
                name = it.name,
                logo = it.logo,
                group = "${it.country.uppercase(Locale.US)} · ${it.group}",
            )
        }.let { all ->
            if (aroundId == null) all.take(limit)
            else {
                val idx = all.indexOfFirst { it.id == aroundId }.coerceAtLeast(0)
                all.drop((idx - limit / 2).coerceAtLeast(0)).take(limit)
            }
        }

    private fun toShow(ch: Channel) = TvShow(
        id = createId(ch),
        title = ch.name,
        poster = ch.logo,
        banner = ch.logo,
        overview = LiveCatalogMeta.overview(
            title = ch.name,
            country = ch.country,
            language = ch.languages.firstOrNull(),
            group = ch.group,
        ),
        genres = buildList {
            if (ch.country.isNotBlank()) {
                add(Genre(LiveCatalogMeta.countryGenreId(ch.country), LiveCatalogMeta.countryName(ch.country)))
            }
            ch.languages.take(2).forEach { lang ->
                add(Genre(LiveCatalogMeta.languageGenreId(lang), LiveCatalogMeta.languageName(lang)))
            }
        },
        providerName = name,
    )
}
