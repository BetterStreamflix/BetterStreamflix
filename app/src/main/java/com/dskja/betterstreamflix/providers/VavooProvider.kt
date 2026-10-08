package com.dskja.betterstreamflix.providers

import android.util.Log
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.*
import com.dskja.betterstreamflix.utils.VavooTls
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.UUID

class VavooProvider(override val language: String) : IptvProvider {

    companion object {
        private const val TAG = "VavooProvider"
        private const val CACHE_DURATION = 30 * 60 * 1000L
        private const val POSTER = "https://www.clipartmax.com/png/full/46-463028_television-images-clip-art.png"

        // VAVOO_LIVE_IOS_PARITY_ANDROID_V1
        // VAVOO_LIVE_EPG_PERFORMANCE_V1
        // VAVOO_EPG_WAIT_FOR_TV_V2
        private data class LiveConfig(
            val apiLanguage: String,
            val apiRegion: String,
            val groups: List<String>,
            val providerName: String
        )

        private val LANG_CONFIG = mapOf(
            "de" to LiveConfig("de", "DE", listOf("Germany", "GERMANY"), "Vavoo Germany Live TV"),
            "en" to LiveConfig("en", "GB", listOf("United Kingdom"), "Vavoo TV Englisch"),
            "it" to LiveConfig("it", "IT", listOf("Italy"), "Vavoo Italy Live TV"),
            "fr" to LiveConfig("fr", "FR", listOf("France", "France Sport"), "Vavoo France Live TV"),
            "es" to LiveConfig("es", "ES", listOf("Spain"), "Vavoo Spain Live TV"),
            "pl" to LiveConfig("pl", "PL", listOf("Poland"), "Vavoo Poland Live TV")
        )

        private const val CATEGORY_PREFIX = "vavoo-live-category:"
        private val CATEGORY_LABELS = linkedMapOf(
            "all" to "Alle",
            "film" to "Film",
            "doku" to "Doku",
            "news" to "News",
            "sport" to "Sport"
        )

        private val FILM_WORDS = listOf(
            "cinema", "movie", "movies", "film", "kino", "sky cinema", "warner", "paramount",
            "action", "thriller", "crime", "comedy", "romance", "horror", "disney+ film"
        )
        private val DOKU_WORDS = listOf(
            "doku", "documentary", "discovery", "national geographic", "nat geo", "history",
            "animal planet", "science", "welt der wunder", "travel", "nature"
        )
        private val NEWS_WORDS = listOf(
            "news", "nachrichten", "tagesschau", "welt", "ntv", "n-tv", "cnn", "bbc news",
            "sky news", "euronews", "bloomberg", "cnbc", "gb news"
        )
        private val SPORT_WORDS = listOf(
            "sport", "sports", "sky sport", "dazn", "eurosport", "espn", "football", "soccer", "fussball",
            "fußball", "bundesliga", "premier league", "tennis", "golf", "nba", "nfl", "formula",
            "f1", "motogp", "racing", "cricket", "magenta sport", "magentasport",
            "sky bundesliga", "sky sport bundesliga", "sportdigital", "sport1", "sport 1",
            "dyn sport", "dyn", "champions league", "europa league", "conference league",
            "dfb pokal", "dfb-pokal", "uefa", "handball", "basketball", "eishockey", "hockey"
        )

        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    override val baseUrl: String = "https://vavoo.to"

    // VAVOO_VYPN_FINAL_DYNAMIC_LIVE_V21
    // LIVE TV follows the user-selected Vavoo site. VOD remains in VavooVodProvider.
    private val liveBaseUrl: String
        get() = baseUrl.trimEnd('/')
    private val CATALOG_URL: String
        get() = "$liveBaseUrl/mediahubmx-catalog.json"
    private val RESOLVE_URL: String
        get() = "$liveBaseUrl/mediahubmx-resolve.json"

    // Cache for home categories per language to avoid instant re-fetching
    private val homeCache = mutableMapOf<String, List<VavooChannel>>()
    private val cacheTimestamps = mutableMapOf<String, Long>()

    // Cache to temporarily map channel IDs to their names when found via search/genres
    private val searchCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    // VAVOO_EXACT_CATALOG_URL_FIX19
    private val channelUrlCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    data class VavooChannel(
        val id: String,
        val name: String,
        val url: String
    )

    // Config for this instance
    private val config = LANG_CONFIG[language] ?: LANG_CONFIG["de"]!!

    override val name: String = config.providerName
    override val logo: String = "$baseUrl/assets/favicon-Djqjt9PL.ico"

    private val primaryGroups: List<String> = config.groups

    // VAVOO_VYPN_FINAL_SESSION_V21
    // Based on the uploaded VYPN 1.9.2 MediaHub behaviour:
    // MediaHubMX + MediaUrl engines, addonSig session, fresh resolve before playback.
    private val VAVOO_API_UA = "okhttp/4.11.0"
    private val VAVOO_PING_UA = "electron-fetch/1.0 electron (+https://github.com/arantes555/electron-fetch)"
    private val VAVOO_PLAY_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

    private enum class MediaEngine(
        val prefix: String,
        val userAgent: String,
        val signatureHeader: String,
    ) {
        MEDIAHUBMX("mediahubmx", "MediaHubMX/2", "mediahubmx-signature"),
        MEDIAURL("mediaurl", "MediaUrl/2", "mediaurl-signature"),
    }

    private data class AddonSignature(
        val value: String,
        val profileVersion: String,
        val createdAt: Long,
    )

    @Volatile private var addonSignature: AddonSignature? = null
    private val addonSignatureLock = Any()
    private val addonSignatureTtlMs = 90 * 1000L

    private fun originOf(url: String): String? = runCatching {
        val parsed = java.net.URI(url)
        val scheme = parsed.scheme ?: return@runCatching null
        val host = parsed.host ?: return@runCatching null
        val port = if (parsed.port > 0) ":${parsed.port}" else ""
        "$scheme://$host$port"
    }.getOrNull()

    private fun resolveBases(vavooUrl: String): List<String> = buildList {
        originOf(vavooUrl)?.let(::add)
        add(liveBaseUrl)
        // Compatibility fallback only. The configured site remains primary.
        if (!liveBaseUrl.equals("https://vavoo.to", ignoreCase = true)) add("https://vavoo.to")
    }.map { it.trimEnd('/') }.distinct()

    private fun signatureBody(version: String): String {
        val now = System.currentTimeMillis()
        val uniqueId = UUID.randomUUID().toString().replace("-", "").take(16)
        return JSONObject().apply {
            put("token", "")
            put("reason", "app-focus")
            put("locale", "de")
            put("theme", "dark")
            put("metadata", JSONObject().apply {
                put("device", JSONObject().apply {
                    put("type", "phone")
                    put("uniqueId", uniqueId)
                })
                put("os", JSONObject().apply {
                    put("name", "android")
                    put("version", "14")
                    put("abis", org.json.JSONArray().put("arm64-v8a"))
                    put("host", "android")
                })
                put("app", JSONObject().apply { put("platform", "android") })
                put("version", JSONObject().apply {
                    put("package", "net.vypn.app")
                    put("binary", version)
                    put("js", version)
                })
            })
            put("appFocusTime", 0)
            put("playerActive", false)
            put("playDuration", 0)
            put("devMode", false)
            put("hasAddon", true)
            put("castConnected", false)
            put("package", "net.vypn.app")
            put("version", version)
            put("process", "app")
            put("firstAppStart", now - 86400000L)
            put("lastAppStart", now)
            put("ipLocation", JSONObject.NULL)
            put("adblockEnabled", true)
            put("migrationApplied", false)
            put("migrationTargetInstalled", false)
            put("proxy", JSONObject().apply {
                put("supported", org.json.JSONArray().put("ss"))
                put("engine", "Mu")
                put("ssVersion", "2022")
                put("enabled", false)
                put("autoServer", true)
                put("id", "")
            })
            put("iap", JSONObject().apply {
                put("supported", false)
                put("error", "")
            })
        }.toString()
    }

    private fun requestVypnSignature(version: String): String? {
        val request = Request.Builder()
            .url("https://www.vypn.net/api/app/ping")
            .post(signatureBody(version).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", VAVOO_PING_UA)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept-Encoding", "gzip")
            .header("Accept-Language", "de")
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "VYPN signature $version HTTP ${response.code}")
                    return@use null
                }
                JSONObject(response.body?.string().orEmpty())
                    .optString("addonSig")
                    .takeIf { it.isNotBlank() }
            }
        }.getOrElse {
            Log.w(TAG, "VYPN signature $version error: ${it.message}")
            null
        }
    }

    private fun requestLegacyVavooSignature(): String? {
        val now = System.currentTimeMillis()
        val uniqueId = UUID.randomUUID().toString().replace("-", "").take(16)
        val body = JSONObject().apply {
            put("token", "")
            put("reason", "app-blur")
            put("locale", "de")
            put("theme", "dark")
            put("metadata", JSONObject().apply {
                put("device", JSONObject().apply {
                    put("type", "Handset")
                    put("brand", "google")
                    put("model", "Nexus")
                    put("name", "21081111RG")
                    put("uniqueId", uniqueId)
                })
                put("os", JSONObject().apply {
                    put("name", "android")
                    put("version", "7.1.2")
                    put("abis", org.json.JSONArray().apply { put("arm64-v8a"); put("armeabi-v7a"); put("armeabi") })
                    put("host", "android")
                })
                put("app", JSONObject().apply {
                    put("platform", "android")
                    put("version", "3.1.20")
                    put("buildId", "289515000")
                    put("engine", "hbc85")
                    put("signatures", org.json.JSONArray().put("6e8a975e3cbf07d5de823a760d4c2547f86c1403105020adee5de67ac510999e"))
                    put("installer", "app.revanced.manager.flutter")
                })
                put("version", JSONObject().apply {
                    put("package", "tv.vavoo.app")
                    put("binary", "3.1.20")
                    put("js", "3.1.20")
                })
            })
            put("appFocusTime", 0)
            put("playerActive", false)
            put("playDuration", 0)
            put("devMode", false)
            put("hasAddon", true)
            put("castConnected", false)
            put("package", "tv.vavoo.app")
            put("version", "3.1.20")
            put("process", "app")
            put("firstAppStart", now)
            put("lastAppStart", now)
            put("ipLocation", "")
            put("adblockEnabled", true)
            put("proxy", JSONObject().apply {
                put("supported", org.json.JSONArray().apply { put("ss"); put("openvpn") })
                put("engine", "ss")
                put("ssVersion", 1)
                put("enabled", true)
                put("autoServer", true)
                put("id", "pl-waw")
            })
            put("iap", JSONObject().apply { put("supported", false) })
        }.toString()

        val request = Request.Builder()
            .url("https://www.vavoo.tv/api/app/ping")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", VAVOO_API_UA)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json; charset=utf-8")
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                JSONObject(response.body?.string().orEmpty()).optString("addonSig").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private fun invalidateAddonSignature() {
        synchronized(addonSignatureLock) { addonSignature = null }
    }

    private fun getAddonSignature(force: Boolean = false): AddonSignature? {
        synchronized(addonSignatureLock) {
            val now = System.currentTimeMillis()
            val cached = addonSignature
            if (!force && cached != null && now - cached.createdAt < addonSignatureTtlMs) return cached

            // Uploaded VYPN is 1.9.2. Current tvvoo still successfully emulates 1.4.1, so keep it as fallback.
            val profiles = listOf("1.9.2", "1.4.1")
            for (version in profiles) {
                val sig = requestVypnSignature(version)
                if (!sig.isNullOrBlank()) {
                    return AddonSignature(sig, version, System.currentTimeMillis()).also { addonSignature = it }
                }
            }
            val legacy = requestLegacyVavooSignature()
            if (!legacy.isNullOrBlank()) {
                return AddonSignature(legacy, "vavoo-3.1.20", System.currentTimeMillis()).also { addonSignature = it }
            }
            addonSignature = null
            return null
        }
    }


    // VAVOO_ORIGINAL_CATALOG_EXACT_RESTORE_FIX19F
    private fun fetchChannels(search: String, group: String, cursor: Int? = null): Pair<List<VavooChannel>, Int?> {
        val filterObj = JSONObject().apply {
            put("group", group)
        }
        val body = JSONObject().apply {
            put("language", config.apiLanguage)
            put("region", config.apiRegion)
            put("catalogId", "iptv")
            put("id", "")
            put("adult", false)
            put("search", search)
            put("sort", "name")
            put("filter", filterObj)
            if (cursor != null) put("cursor", cursor) else put("cursor", JSONObject.NULL)
        }.toString()

        return try {
            val request = Request.Builder()
                .url(CATALOG_URL)
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Origin", baseUrl)
                .header("Referer", "$baseUrl/")
                .build()

            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: return Pair(emptyList(), null))
            val items = json.optJSONArray("items") ?: return Pair(emptyList(), null)
            val nextCursor = if (json.isNull("nextCursor")) null else json.optInt("nextCursor")

            val channels = (0 until items.length()).mapNotNull { i ->
                val item = items.getJSONObject(i)
                val url = item.optString("url").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val name = item.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                VavooChannel(
                    id = item.optJSONObject("ids")?.optString("id") ?: url,
                    name = name,
                    url = url
                )
            }
            Pair(channels, nextCursor)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching channels (search='$search', group='$group'): ${e.message}")
            Pair(emptyList(), null)
        }
    }

    private fun categoryFor(channelName: String): String {
        val value = channelName.lowercase()
        return when {
            SPORT_WORDS.any(value::contains) -> "sport"
            NEWS_WORDS.any(value::contains) -> "news"
            DOKU_WORDS.any(value::contains) -> "doku"
            FILM_WORDS.any(value::contains) -> "film"
            else -> "other"
        }
    }

    private fun filterCategory(channels: List<VavooChannel>, category: String): List<VavooChannel> =
        when (category) {
            "all" -> channels
            "film", "doku", "news", "sport" -> channels.filter { categoryFor(it.name) == category }
            else -> channels
        }

    private fun allHomeChannels(): List<VavooChannel> =
        primaryGroups.flatMap(::loadHomeGroupChannels)
            .distinctBy { it.id }

    private fun cachedHomeChannels(): List<VavooChannel> =
        primaryGroups.flatMap { homeCache[it].orEmpty() }
            .distinctBy { it.id }

    private fun asTvShow(channel: VavooChannel): TvShow {
        searchCache[channel.id] = channel.name
        channelUrlCache[channel.id] = channel.url
        return TvShow(
            id = channel.id,
            title = channel.name,
            poster = POSTER,
            banner = POSTER,
            overview = "Vavoo Live TV"
        )
    }

    private fun loadHomeGroupChannels(group: String): List<VavooChannel> {
        val now = System.currentTimeMillis()
        val cached = homeCache[group]
        if (cached != null && (now - (cacheTimestamps[group] ?: 0)) < CACHE_DURATION) {
            return cached
        }
        val (channels, _) = fetchChannels("", group)
        if (channels.isNotEmpty()) {
            homeCache[group] = channels
            cacheTimestamps[group] = now
        }
        return channels
    }

    data class ResolvedChannel(val name: String, val url: String)

    private data class ResolveAttemptResult(
        val httpCode: Int,
        val resolved: ResolvedChannel?,
    )

    private fun parseResolvedChannel(raw: String): ResolvedChannel? {
        if (raw.isBlank()) return null
        val trimmed = raw.trimStart()
        val obj = if (trimmed.startsWith("[")) {
            val arr = runCatching { org.json.JSONArray(raw) }.getOrNull()
            if (arr == null || arr.length() == 0) null else arr.optJSONObject(0)
        } else {
            runCatching { JSONObject(raw) }.getOrNull()?.let { top ->
                top.optJSONObject("data") ?: top.optJSONObject("result") ?: top
            }
        } ?: return null
        val url = obj.optString("url").takeIf { it.isNotBlank() } ?: return null
        return ResolvedChannel(obj.optString("name"), url)
    }

    private fun unsignedResolve(vavooUrl: String, apiBase: String): ResolveAttemptResult {
        val body = JSONObject().apply {
            put("language", config.apiLanguage)
            put("region", config.apiRegion)
            put("url", vavooUrl)
        }.toString()
        val endpoint = "${apiBase.trimEnd('/')}/mediahubmx-resolve.json"
        val request = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .header("Origin", apiBase.trimEnd('/'))
            .header("Referer", "${apiBase.trimEnd('/')}/")
            .build()
        return client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            Log.d(TAG, "LIVE original resolve ${response.code} $endpoint")
            ResolveAttemptResult(response.code, if (response.isSuccessful) parseResolvedChannel(raw) else null)
        }
    }

    private fun signedResolve(
        vavooUrl: String,
        apiBase: String,
        engine: MediaEngine,
        signature: AddonSignature,
        clientVersion: String,
    ): ResolveAttemptResult {
        val endpoint = "${apiBase.trimEnd('/')}/${engine.prefix}-resolve.json"
        val body = JSONObject().apply {
            put("language", config.apiLanguage)
            put("region", config.apiRegion)
            put("url", vavooUrl)
            put("clientVersion", clientVersion)
        }.toString()
        val request = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", engine.userAgent)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept-Encoding", "gzip")
            .header(engine.signatureHeader, signature.value)
            .build()
        return client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            Log.d(TAG, "LIVE ${engine.prefix} resolve ${response.code} base=$apiBase client=$clientVersion sig=${signature.profileVersion}")
            ResolveAttemptResult(response.code, if (response.isSuccessful) parseResolvedChannel(raw) else null)
        }
    }

    private fun resolveChannel(vavooUrl: String): ResolvedChannel? {
        val bases = resolveBases(vavooUrl)

        // 1) Keep the old Android route first. This is the path that previously played a useful subset.
        for (apiBase in bases) {
            val legacy = runCatching { unsignedResolve(vavooUrl, apiBase) }
                .onFailure { Log.w(TAG, "LIVE original resolve error on $apiBase: ${it.message}") }
                .getOrNull()
            if (legacy?.resolved != null) return legacy.resolved
        }

        // 2) VYPN-style addon session. Signature and resolve are made from the same Android device/IP.
        fun runSignedPass(signature: AddonSignature): Pair<ResolvedChannel?, Boolean> {
            var authFailure = false
            val plans = listOf(
                MediaEngine.MEDIAHUBMX to "3.1.0",
                MediaEngine.MEDIAURL to "3.1.0",
                MediaEngine.MEDIAHUBMX to "3.0.2",
                MediaEngine.MEDIAURL to "3.0.2",
            )
            for (apiBase in bases) {
                for ((engine, clientVersion) in plans) {
                    val result = runCatching { signedResolve(vavooUrl, apiBase, engine, signature, clientVersion) }
                        .onFailure { Log.w(TAG, "LIVE signed ${engine.prefix} error on $apiBase: ${it.message}") }
                        .getOrNull() ?: continue
                    if (result.resolved != null) return result.resolved to authFailure
                    if (result.httpCode in listOf(401, 403, 451)) authFailure = true
                }
            }
            return null to authFailure
        }

        val firstSig = getAddonSignature(force = false)
        if (firstSig != null) {
            val (resolved, authFailure) = runSignedPass(firstSig)
            if (resolved != null) return resolved
            if (authFailure) {
                invalidateAddonSignature()
                val freshSig = getAddonSignature(force = true)
                if (freshSig != null) {
                    val (retryResolved, _) = runSignedPass(freshSig)
                    if (retryResolved != null) return retryResolved
                }
            }
        }

        Log.w(TAG, "LIVE all resolve strategies failed for $vavooUrl")
        return null
    }


    override suspend fun getHome(): List<Category> {
        val channels = allHomeChannels()

        return listOf(
            Category(
                name = if (language == "en") "Vavoo United Kingdom Live TV" else name,
                list = channels.take(300).map(::asTvShow)
            )
        )
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        val cleanQuery = query.trim()
        val cursor = if (page > 1) (page - 1) * 300 else null

        // Fast primary path: Vavoo itself searches channel names.
        val byChannelName = primaryGroups
            .flatMap { group -> fetchChannels(cleanQuery, group, cursor).first }

        return byChannelName
            .distinctBy { it.id }
            .map(::asTvShow)
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val cached = if (page == 1) cachedHomeChannels() else emptyList()
        val channels = if (cached.isNotEmpty()) {
            cached
        } else {
            val cursor = if (page > 1) (page - 1) * 300 else null
            primaryGroups.flatMap { group -> fetchChannels("", group, cursor).first }
                .distinctBy { it.id }
        }
        return channels.map(::asTvShow)
    }

    override suspend fun getMovie(id: String): Movie = Movie(id = id, title = "Live", poster = "")

    override suspend fun getTvShow(id: String): TvShow {
        // 1. Try in-memory caches first (fast path)
        var cachedName: String? = null
        for (group in primaryGroups) {
            val found = homeCache[group]?.find { it.id == id }
            if (found != null) {
                cachedName = found.name
                break
            }
        }
        if (cachedName == null) {
            cachedName = searchCache[id]
        }

        // 2. If not found (e.g. after app restart), call the resolve API:
        //    the response contains the real channel name on the "name" field
        val title = cachedName ?: run {
            val vavooUrl = channelUrlCache[id] ?: "$baseUrl/vavoo-iptv/play/$id"
            val resolved = resolveChannel(vavooUrl)
            if (resolved != null && resolved.name.isNotEmpty()) {
                searchCache[id] = resolved.name
                resolved.name
            } else {
                id
            }
        }

        return TvShow(
            id = id,
            title = title,
            poster = POSTER,
            banner = POSTER,
            overview = "Vavoo Live TV",
            seasons = listOf(Season(id = id, number = 1, title = "Watch"))
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        return listOf(Episode(id = seasonId, number = 1, title = "Watch Now", season = null))
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        if (id.startsWith(CATEGORY_PREFIX)) {
            val category = id.removePrefix(CATEGORY_PREFIX)
            val label = CATEGORY_LABELS[category] ?: category
            val channels = filterCategory(allHomeChannels(), category)
            val tvShows = channels.map(::asTvShow)
            return Genre(id = id, name = label, shows = tvShows)
        }

        val cursor = if (page > 1) (page - 1) * 300 else null
        val channels = primaryGroups
            .flatMap { group -> fetchChannels(id, group, cursor).first }
            .distinctBy { it.id }
        val tvShows = channels.map(::asTvShow)
        return Genre(id = id, name = id, shows = tvShows)
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = "Vavoo", image = logo, biography = "", birthday = "", deathday = "", placeOfBirth = "")
    }

    // VAVOO_TVVOO_PLAYBACK_FALLBACK_V1
    // Vavoo exposes the same channel through several independent source variants
    // (.b/.c/.s, HD, RAW, BACKUP, ...). Keep the user's selected source first,
    // but automatically try sibling sources when resolve/playback probing fails.
    private fun channelFamilyDisplayName(name: String): String {
        var value = name.trim()
        repeat(4) {
            value = value
                .replace(Regex("""(?i)\s+\.(?:b|c|s)\s*$"""), "")
                .replace(Regex("""(?i)\s*\((?:backup|bak|alt)\)\s*$"""), "")
                .replace(Regex("""(?i)\s+(?:uhd|fhd|hd\+?|raw|backup)\s*$"""), "")
                .trim()
        }
        return value
    }

    private fun channelFamilyKey(name: String): String =
        channelFamilyDisplayName(name)
            .lowercase()
            .replace("ß", "ss")
            .replace(Regex("""[^a-z0-9]+"""), "")

    private fun channelById(id: String): VavooChannel? =
        cachedHomeChannels().firstOrNull { it.id == id }

    private fun playbackCandidates(server: Video.Server): List<VavooChannel> {
        val selectedUrl = when {
            server.src.startsWith("http") -> server.src
            server.id.startsWith("http") -> server.id
            !channelUrlCache[server.id].isNullOrBlank() -> channelUrlCache[server.id]!!
            else -> "$liveBaseUrl/vavoo-iptv/play/${server.id}"
        }

        val selectedCached = channelById(server.id)
        val selectedName =
            selectedCached?.name
                ?: searchCache[server.id]
                ?: cachedHomeChannels().firstOrNull { it.url == selectedUrl }?.name

        val result = linkedMapOf<String, VavooChannel>()
        fun add(channel: VavooChannel) {
            if (channel.url.isNotBlank()) result.putIfAbsent(channel.url, channel)
        }

        // Always preserve the exact catalog URL/source selected by the user.
        add(
            selectedCached ?: VavooChannel(
                id = server.id,
                name = selectedName ?: server.name,
                url = selectedUrl,
            )
        )

        if (!selectedName.isNullOrBlank()) {
            val familyKey = channelFamilyKey(selectedName)

            cachedHomeChannels()
                .filter { channelFamilyKey(it.name) == familyKey }
                .forEach(::add)

            // Search Vavoo again at playback time so fallback does not depend on
            // a warm home/search cache after app restarts.
            val query = channelFamilyDisplayName(selectedName)
            if (query.isNotBlank()) {
                primaryGroups
                    .flatMap { group -> fetchChannels(query, group).first }
                    .filter { channelFamilyKey(it.name) == familyKey }
                    .forEach(::add)
            }
        }

        return result.values.toList()
    }

    private fun resolvedPlaybackHeaders(vavooUrl: String): Map<String, String> {
        val playbackOrigin = originOf(vavooUrl) ?: liveBaseUrl
        return mapOf(
            "User-Agent" to VAVOO_PLAY_UA,
            "Referer" to "${playbackOrigin.trimEnd('/')}/",
        )
    }

    private fun probeResolvedStream(resolvedUrl: String, vavooUrl: String): Boolean {
        val host = runCatching { java.net.URI(resolvedUrl).host }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return false

        val builder = client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)

        // Some current Vavoo CDN nodes serve a valid HLS stream with an expired
        // certificate. Relax TLS only in this Vavoo-only probe client.
        VavooTls.applyTo(builder, host)
        val probeClient = builder.build()
        val headers = resolvedPlaybackHeaders(vavooUrl)

        return runCatching {
            val playlistRequest = Request.Builder()
                .url(resolvedUrl)
                .get()
                .apply { headers.forEach { (key, value) -> header(key, value) } }
                .build()

            probeClient.newCall(playlistRequest).execute().use { playlistResponse ->
                if (!playlistResponse.isSuccessful) return@use false

                val contentType = playlistResponse.header("Content-Type").orEmpty().lowercase()
                val finalUrl = playlistResponse.request.url

                val looksLikeHls =
                    contentType.contains("mpegurl") ||
                        finalUrl.encodedPath.endsWith(".m3u8", ignoreCase = true) ||
                        resolvedUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

                if (!looksLikeHls) {
                    val stream = playlistResponse.body?.byteStream() ?: return@use false
                    return@use stream.read(ByteArray(1024)) > 0
                }

                val playlist = playlistResponse.body?.string().orEmpty()
                if (!playlist.contains("#EXTM3U", ignoreCase = true)) return@use false

                val nextResource = playlist.lineSequence()
                    .map(String::trim)
                    .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                    ?: Regex("""URI=[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
                        .find(playlist)
                        ?.groupValues
                        ?.getOrNull(1)
                    ?: return@use true

                val segmentUrl = finalUrl.resolve(nextResource) ?: return@use false
                val segmentRequest = Request.Builder()
                    .url(segmentUrl)
                    .get()
                    .header("Range", "bytes=0-8191")
                    .apply { headers.forEach { (key, value) -> header(key, value) } }
                    .build()

                probeClient.newCall(segmentRequest).execute().use { segmentResponse ->
                    if (!segmentResponse.isSuccessful && segmentResponse.code != 206) {
                        return@use false
                    }
                    val stream = segmentResponse.body?.byteStream() ?: return@use false
                    stream.read(ByteArray(8192)) > 0
                }
            }
        }.onFailure {
            Log.w(TAG, "LIVE probe failed host=$host: ${it.message}")
        }.getOrDefault(false)
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        // Carry the exact catalog URL into playback. Do not depend solely on an in-memory cache.
        val exactUrl = if (id.startsWith("http")) id else channelUrlCache[id].orEmpty()
        return listOf(Video.Server(id = id, name = "Vavoo", src = exactUrl))
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val candidates = playbackCandidates(server)
        Log.d(
            TAG,
            "[$language] Playback candidates: " +
                candidates.joinToString { "${it.name}=${it.url}" }
        )

        var lastError: Throwable? = null

        for ((index, candidate) in candidates.withIndex()) {
            val vavooUrl = candidate.url
            Log.d(TAG, "[$language] Resolve candidate ${index + 1}/${candidates.size}: ${candidate.name} -> $vavooUrl")

            val resolved = runCatching { resolveChannel(vavooUrl) }
                .onFailure { lastError = it }
                .getOrNull()
                ?: continue

            if (!probeResolvedStream(resolved.url, vavooUrl)) {
                Log.w(TAG, "[$language] Candidate failed playback probe: ${candidate.name} -> ${resolved.url}")
                continue
            }

            Log.d(TAG, "[$language] Playback candidate OK: ${candidate.name} -> ${resolved.url}")
            return Video(
                source = resolved.url,
                subtitles = emptyList(),
                headers = resolvedPlaybackHeaders(vavooUrl),
                maintainToken = false,
            )
        }

        throw Exception(
            "Vavoo: all matching live sources failed for ${server.id}",
            lastError,
        )
    }
}

