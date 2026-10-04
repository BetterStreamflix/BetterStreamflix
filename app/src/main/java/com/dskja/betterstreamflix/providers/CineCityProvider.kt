package com.dskja.betterstreamflix.providers

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.M3uChannelIdCodec
import com.dskja.betterstreamflix.utils.M3uPlaylistParser

import android.util.Base64
import android.util.Log
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.*
import okhttp3.*
import java.util.concurrent.TimeUnit

object CineCityProvider : IptvProvider, ProviderConfigUrl {

    override val name = "MAGISTV"
    override val defaultBaseUrl = "https://raw.githubusercontent.com"
    override val baseUrl: String
        get() = UserPreferences.getProviderCache(this, UserPreferences.PROVIDER_URL).ifBlank { defaultBaseUrl }
    override val changeUrlMutex = Mutex()

    override suspend fun onChangeUrl(forceRefresh: Boolean): String = changeUrlMutex.withLock {
        baseUrl
    }
    override val logo = "https://i.ibb.co/39Ld2wbt/MAGISTV.png"
    override val language = "es"

    private const val TAG = "CineCityProvider"

    private const val OBFUSCATED_PLAYLIST = "aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL0NJTkVDSVRZMjAyMy9jaW5lY2l0eS9jaW5lY2l0eS5uZXQvcHJpbmNpcGFsLm0zdQ=="

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            private val cookieStore = mutableMapOf<String, List<Cookie>>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: listOf()
            }
        })
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36")
                .build()
            chain.proceed(request)
        }
        .build()

    private var cachedChannels: List<M3UChannel>? = null
    private var lastFetchTime: Long = 0
    private const val CACHE_DURATION = 30 * 60 * 1000

    data class M3UChannel(
        val name: String,
        val url: String,
        val logo: String?,
        val group: String?,
        val userAgent: String? = null,
        val referrer: String? = null,
        val origin: String? = null,
    )

    private fun createId(channel: M3UChannel): String {
        return M3uChannelIdCodec.encode(
            url = channel.url,
            name = channel.name,
            logo = channel.logo,
            userAgent = channel.userAgent,
            referrer = channel.referrer,
            origin = channel.origin,
        )
    }

    private fun decodeId(id: String): Triple<String, String, String> {
        if (id == "creador-info" || id == "apoyo-nando") {
            return Triple(id, "", "")
        }
        val payload = M3uChannelIdCodec.decode(id)
        return Triple(payload.url, payload.name, payload.logo)
    }


    private fun getAllChannels(): List<M3UChannel> {
        val now = System.currentTimeMillis()
        val cached = cachedChannels
        if (cached != null && (now - lastFetchTime) < CACHE_DURATION) return cached

        return try {
            val decodedUrl = String(Base64.decode(OBFUSCATED_PLAYLIST, Base64.DEFAULT))
            val request = Request.Builder().url(decodedUrl).build()
            val body = client.newCall(request).execute().use { it.body?.string() }
            if (body.isNullOrBlank()) return emptyList()
            val channels = parseM3U(body)
            Log.d(TAG, "MAGISTV channels=${channels.size}")
            cachedChannels = channels
            lastFetchTime = now
            channels
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching MAGISTV M3U: ${e.message}")
            cachedChannels ?: emptyList()
        }
    }

    override suspend fun getHome(): List<Category> {
        val channels = getAllChannels()
        val categories = mutableListOf<Category>()

        val channelCategories = channels
            .mapNotNull { ch -> ch.group?.takeIf { it.isNotEmpty() }?.let { g -> g to ch } }
            .groupBy({ it.first }, { it.second })
            .map { (groupName, channelList) ->
                Category(
                    name = groupName,
                    list = channelList.distinctBy { it.name }.take(30).map { channel ->
                        TvShow(id = createId(channel), title = channel.name, poster = channel.logo ?: "", banner = channel.logo ?: "")
                    }
                )
            }.sortedBy { it.name }

        categories.addAll(channelCategories)

        val ungrouped = channels.filter { it.group.isNullOrEmpty() }
        if (ungrouped.isNotEmpty()) {
            categories.add(
                Category(
                    name = "General / Sin Categoría",
                    list = ungrouped.distinctBy { it.name }.take(30).map { channel ->
                        TvShow(id = createId(channel), title = channel.name, poster = channel.logo ?: "", banner = channel.logo ?: "")
                    }
                )
            )
        }

        categories.add(Category(name = "Soporte y Ayuda", list = listOf(getInfoItem("creador-info"), getInfoItem("apoyo-nando"))))
        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        val allChannels = getAllChannels()
        return allChannels.filter {
            it.name.contains(query, ignoreCase = true) || (it.group?.contains(query, ignoreCase = true) == true)
        }.distinctBy { it.name }.take(80).map { channel ->
            TvShow(id = createId(channel), title = channel.name, poster = channel.logo ?: "")
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val groupChannels = getAllChannels().filter {
            it.group?.contains(id, ignoreCase = true) ?: false
        }.distinctBy { it.name }
        val pagedList = groupChannels.drop((page - 1) * 40).take(40).map { channel ->
            TvShow(id = createId(channel), title = channel.name, poster = channel.logo ?: "")
        }
        return Genre(id = id, name = id, shows = pagedList)
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = "CineCity", image = logo, biography = "", birthday = "", deathday = "", placeOfBirth = "")
    }

    override suspend fun getTvShow(id: String): TvShow {
        if (id == "creador-info" || id == "apoyo-nando") return getInfoItem(id)
        val (_, name, logo) = decodeId(id)
        return TvShow(
            id = id, title = name, poster = logo, banner = logo,
            overview = "Transmisión: $name\nFuente: CineCity M3U.",
            seasons = listOf(Season(id = id, number = 1, title = "Live"))
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        if (seasonId == "creador-info" || seasonId == "apoyo-nando") return emptyList()
        return listOf(Episode(id = seasonId, number = 1, title = "Ver Ahora", season = null))
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        if (id == "creador-info" || id == "apoyo-nando") return emptyList()
        return listOf(Video.Server(id = id, name = "CineCity Stream"))
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val payload = M3uChannelIdCodec.decode(server.id)
        if (!M3uPlaylistParser.isPlayableUrl(payload.url)) {
            throw Exception("MAGISTV: channel URL missing or dead placeholder (try another channel)")
        }
        val headers = M3uChannelIdCodec.playbackHeaders(server.id).toMutableMap()
        if (!headers.containsKey("User-Agent")) {
            headers["User-Agent"] =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36"
        }
        return Video(
            source = payload.url,
            subtitles = emptyList(),
            headers = headers,
        )
    }

    private fun getInfoItem(id: String): TvShow {
        val isReport = id == "creador-info"
        return TvShow(
            id = id,
            title = if (isReport) "Reportar problemas" else "Apoya al Proveedor",
            poster = if (isReport) "https://i.ibb.co/dsknGBHT/Imagen-de-Whats-App-2025-09-06-a-las-19-00-50-e8e5bcaa.jpg" else "https://i.ibb.co/B5gKLkqS/nuevo-formato-2-K-202604112205.jpg",
            banner = if (isReport) "https://i.ibb.co/dsknGBHT/Imagen-de-Whats-App-2025-09-06-a-las-19-00-50-e8e5bcaa.jpg" else "https://i.ibb.co/B5gKLkqS/nuevo-formato-2-K-202604112205.jpg",
            overview = if (isReport) "Si algún canal no funciona, por favor repórtalo en Telegram." else "Donación voluntaria para los servidores.",
            seasons = emptyList()
        )
    }

    private fun parseM3U(m3uRaw: String): List<M3UChannel> =
        M3uPlaylistParser.parse(m3uRaw).map {
            M3UChannel(
                name = it.name,
                url = it.url,
                logo = it.logo,
                group = it.group,
                userAgent = it.userAgent,
                referrer = it.referrer,
                origin = it.origin,
            )
        }

    override suspend fun listLiveChannels(aroundId: String?, limit: Int): List<com.dskja.betterstreamflix.iptv.IptvLiveSession.Channel> {
        val channels = getAllChannels()
            .filter { it.name.isNotBlank() }
            .distinctBy { it.name }
            .map { channel ->
                com.dskja.betterstreamflix.iptv.IptvLiveSession.Channel(
                    id = createId(channel),
                    name = channel.name,
                    logo = channel.logo,
                    group = channel.group,
                    programNow = channel.group?.takeIf { it.isNotBlank() },
                )
            }
        return com.dskja.betterstreamflix.iptv.IptvChannelWindow.fromChannels(channels, aroundId, limit)
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val channels = getAllChannels()
        val start = (page - 1) * 50
        if (start >= channels.size) return emptyList()
        return channels.drop(start).take(50).map { channel ->
            TvShow(id = createId(channel), title = channel.name, poster = channel.logo ?: "")
        }
    }

    override suspend fun getMovie(id: String): Movie = Movie(id = id, title = "Live", poster = "")
}