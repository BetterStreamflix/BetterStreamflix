package com.dskja.betterstreamflix.utils

import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Resolves poster/cover art for live IPTV channels that ship without logos.
 * Prefers CDN Live TV channel images (same catalog as StreamSports99), then a
 * deterministic initials avatar so grids never look empty.
 */
object ChannelCoverResolver {

    private const val TAG = "ChannelCover"
    private const val CDN_CHANNELS =
        "https://cdnlivetv.is/api/v1/channels/?user=cdnlivetv&plan=free"
    private const val CACHE_MS = 6 * 60 * 60 * 1000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val mutex = Mutex()
    @Volatile private var logoByName: Map<String, String> = emptyMap()
    @Volatile private var lastFetch = 0L

    private val memory = ConcurrentHashMap<String, String>()

    suspend fun warm() {
        ensureIndex()
    }

    fun resolveSync(
        channelName: String,
        existing: String? = null,
        providerFallback: String? = null,
    ): String {
        val trimmed = existing?.trim().orEmpty()
        if (trimmed.isNotBlank()) return trimmed
        val key = normalize(channelName)
        memory[key]?.let { return it }
        logoByName[key]?.let {
            memory[key] = it
            return it
        }
        // Fuzzy: strip common suffixes and retry.
        val stripped = key
            .removeSuffix(" usa").removeSuffix(" us").removeSuffix(" uk")
            .removeSuffix(" hd").removeSuffix(" fhd").removeSuffix(" 4k")
            .trim()
        if (stripped.isNotBlank() && stripped != key) {
            logoByName[stripped]?.let {
                memory[key] = it
                return it
            }
        }
        logoByName.entries.firstOrNull { (k, _) ->
            k.contains(key) || key.contains(k)
        }?.value?.let {
            memory[key] = it
            return it
        }
        val fallback = providerFallback?.trim().orEmpty()
        if (fallback.isNotBlank()) return fallback
        return initialsAvatar(channelName)
    }

    suspend fun resolve(
        channelName: String,
        existing: String? = null,
        providerFallback: String? = null,
    ): String {
        ensureIndex()
        return resolveSync(channelName, existing, providerFallback)
    }

    private suspend fun ensureIndex() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (logoByName.isNotEmpty() && now - lastFetch < CACHE_MS) return@withContext
        mutex.withLock {
            if (logoByName.isNotEmpty() && now - lastFetch < CACHE_MS) return@withLock
            val map = fetchCdnLogos()
            if (map.isNotEmpty()) {
                logoByName = map
                lastFetch = now
                Log.d(TAG, "Cached ${map.size} CDN channel logos")
            }
        }
    }

    private fun fetchCdnLogos(): Map<String, String> {
        return try {
            val req = Request.Builder()
                .url(CDN_CHANNELS)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                )
                .header("Referer", "https://streamsports99.su/")
                .build()
            val body = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            val arr = JSONObject(body).optJSONArray("channels") ?: return emptyMap()
            val out = HashMap<String, String>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                var image = o.optString("image").trim()
                if (name.isBlank() || image.isBlank()) continue
                image = image.replace("cdnlivetv.tv", "cdnlivetv.is")
                out[normalize(name)] = image
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "CDN logo index failed: ${e.message}")
            emptyMap()
        }
    }

    fun normalize(name: String): String =
        name.lowercase(Locale.US)
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()

    fun initialsAvatar(channelName: String): String {
        val initials = channelName
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifBlank { "TV" }
        val hue = (normalize(channelName).hashCode() and 0xFFFFFF).toString(16).padStart(6, '0')
        val encodedName = java.net.URLEncoder.encode(initials, Charsets.UTF_8.name())
        return "https://ui-avatars.com/api/?name=$encodedName&background=${hue.take(6)}" +
            "&color=ffffff&size=256&bold=true&format=png"
    }
}
