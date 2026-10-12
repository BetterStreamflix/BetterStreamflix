package com.dskja.betterstreamflix.iptv

import android.os.SystemClock
import android.util.Log
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.providers.IptvProvider
import com.dskja.betterstreamflix.providers.Provider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Warms getServers (+ optional getVideo for the first server) for ±N neighbors
 * so channel zapping skips a full cold resolve on the critical path.
 *
 * Server lists are keyed by `provider|channelId` (ZP-003).
 */
object IptvZapPreloader {

    private const val TAG = "IptvZapPreloader"
    private const val MAX_ENTRIES = 16
    private const val MAX_VIDEO_ENTRIES = 8
    /** Default zap warm radius (±2) for stabler Live Hub / IPTV hopping. */
    const val DEFAULT_WARM_RADIUS = 2
    /** Warm server lists go stale quickly on rotating IPTV CDNs. */
    private const val TTL_MS = 90_000L
    /** Keep last-good m3u8 briefly so zap does not re-scrape immediately. */
    private const val VIDEO_TTL_MS = 120_000L

    /** Overridable for JVM unit tests (SystemClock is not mocked there). */
    @Volatile
    internal var elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() }

    private data class CacheEntry(
        val servers: List<Video.Server>,
        val storedAtMs: Long,
    )

    private data class CachedVideo(
        val video: Video,
        // Use elapsedRealtime to match servers TTL clock (avoid wall-clock skew).
        val cachedAtMs: Long,
    ) {
        fun isFresh(now: Long = elapsedRealtimeMs()): Boolean =
            now - cachedAtMs < VIDEO_TTL_MS
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicInteger()
    /** Access-order LRU; guarded by itself. Keys are provider|channelId. */
    private val serversByChannelKey =
        object : LinkedHashMap<String, CacheEntry>(16, 0.75f, /* accessOrder = */ true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
                size > MAX_ENTRIES
        }
    /** Access-order LRU for warmed videos; guarded by itself. */
    private val videoByServerId =
        object : LinkedHashMap<String, CachedVideo>(16, 0.75f, /* accessOrder = */ true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedVideo>?): Boolean =
                size > MAX_VIDEO_ENTRIES
        }
    private val inflight = ConcurrentHashMap<String, Job>()

    fun cacheKey(providerName: String, channelId: String): String =
        "${providerName.trim()}|${channelId.trim()}"

    fun clear() {
        generation.incrementAndGet()
        inflight.values.forEach { it.cancel() }
        inflight.clear()
        synchronized(serversByChannelKey) {
            serversByChannelKey.clear()
        }
        synchronized(videoByServerId) {
            videoByServerId.clear()
        }
    }

    /** Cancel in-flight warms without dropping LRU cache (ZP-008 hub destroy). */
    fun cancelInflight() {
        inflight.values.forEach { it.cancel() }
        inflight.clear()
    }

    /** Consume a warm entry (remove-on-take). Expired entries are dropped. */
    fun takeCachedServers(providerName: String, channelId: String): List<Video.Server>? {
        val key = cacheKey(providerName, channelId)
        synchronized(serversByChannelKey) {
            val entry = serversByChannelKey.remove(key) ?: return null
            if (elapsedRealtimeMs() - entry.storedAtMs > TTL_MS) return null
            return entry.servers.takeIf { it.isNotEmpty() }
        }
    }

    fun peekCachedServers(providerName: String, channelId: String): List<Video.Server>? {
        val key = cacheKey(providerName, channelId)
        synchronized(serversByChannelKey) {
            val entry = serversByChannelKey[key] ?: return null
            if (elapsedRealtimeMs() - entry.storedAtMs > TTL_MS) {
                serversByChannelKey.remove(key)
                return null
            }
            return entry.servers.takeIf { it.isNotEmpty() }
        }
    }

    fun invalidate(providerName: String, channelId: String) {
        val key = cacheKey(providerName, channelId)
        val servers = synchronized(serversByChannelKey) {
            serversByChannelKey.remove(key)?.servers
        }
        // Also drop warmed m3u8 for this channel — otherwise peek re-serves dead CDN URLs ≤ VIDEO_TTL.
        if (servers != null) {
            synchronized(videoByServerId) {
                servers.forEach { server -> videoByServerId.remove(server.id) }
            }
        }
        inflight.remove(key)?.cancel()
    }

    /** Remember a resolved server list (Mini-Live preview / zap) without a network round-trip. */
    fun rememberServers(providerName: String, channelId: String, servers: List<Video.Server>) {
        if (providerName.isBlank() || channelId.isBlank() || servers.isEmpty()) return
        val key = cacheKey(providerName, channelId)
        synchronized(serversByChannelKey) {
            serversByChannelKey[key] = CacheEntry(servers, storedAtMs = elapsedRealtimeMs())
        }
    }

    /** Remember a warmed video for [serverId] (Mini-Live preview → promote). */
    fun rememberVideo(serverId: String, video: Video) {
        if (serverId.isBlank() || video.source.isBlank()) return
        synchronized(videoByServerId) {
            purgeStaleVideosLocked()
            videoByServerId[serverId] = CachedVideo(video, cachedAtMs = elapsedRealtimeMs())
        }
    }

    /** Test harness: seed warm caches without launching network work. */
    internal fun seedForTest(
        providerName: String,
        channelId: String,
        servers: List<Video.Server>,
        video: Video? = null,
    ) {
        rememberServers(providerName, channelId, servers)
        val firstId = servers.firstOrNull()?.id
        if (video != null && firstId != null) {
            rememberVideo(firstId, video)
        }
    }

    /** Drop a single warmed video (e.g. after getVideo failure when servers entry already gone). */
    fun invalidateVideo(serverId: String) {
        synchronized(videoByServerId) {
            videoByServerId.remove(serverId)
        }
    }

    fun takeCachedVideo(serverId: String): Video? {
        synchronized(videoByServerId) {
            val hit = videoByServerId.remove(serverId) ?: return null
            if (!hit.isFresh()) return null
            return hit.video
        }
    }

    /** Non-destructive warm hit — preferred on the zap critical path. */
    fun peekCachedVideo(serverId: String): Video? {
        synchronized(videoByServerId) {
            val hit = videoByServerId[serverId] ?: return null
            if (!hit.isFresh()) {
                videoByServerId.remove(serverId)
                return null
            }
            return hit.video
        }
    }

    fun warmNeighbors(provider: Provider, radius: Int = DEFAULT_WARM_RADIUS) {
        if (provider !is IptvProvider) return
        // SE-008: Stick Energy caps warm radius to 0–1 and serializes via single-job gate.
        val effectiveRadius = com.dskja.betterstreamflix.ui.StickEnergyMode.zapWarmRadius(radius)
        if (effectiveRadius <= 0) return
        val neighbors = IptvLiveSession.neighbors(effectiveRadius)
        if (neighbors.isEmpty()) return
        neighbors.forEach { channel ->
            warmChannel(provider, channel)
        }
    }

    fun warmChannel(provider: Provider, channel: IptvLiveSession.Channel) {
        if (provider !is IptvProvider) return
        val key = cacheKey(provider.name, channel.id)
        val serversHit = peekCachedServers(provider.name, channel.id)
        val firstServerId = serversHit?.firstOrNull()?.id
        val freshVideo = firstServerId?.let { peekCachedVideo(it) }
        if (serversHit != null && freshVideo != null) {
            return
        }
        if (inflight.containsKey(key)) return
        val gen = generation.get()
        // Register inflight before launch so a concurrent warm cannot race.
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val episode = IptvLiveSession.toEpisodeType(channel)
                val servers = provider.getServers(channel.id, episode)
                if (!isActive || gen != generation.get() || servers.isEmpty()) return@launch
                synchronized(serversByChannelKey) {
                    if (gen != generation.get()) return@synchronized
                    serversByChannelKey[key] = CacheEntry(servers, storedAtMs = elapsedRealtimeMs())
                }
                Log.d(TAG, "warmed ${channel.name} (${servers.size} servers)")

                // Also warm getVideo for the first server so zap skips HTML scrape.
                val first = servers.firstOrNull() ?: return@launch
                if (peekCachedVideo(first.id) != null) return@launch
                val video = runCatching { provider.getVideo(first) }.getOrNull() ?: return@launch
                if (!isActive || gen != generation.get()) return@launch
                if (video.source.isBlank()) return@launch
                synchronized(videoByServerId) {
                    if (gen != generation.get()) return@synchronized
                    purgeStaleVideosLocked()
                    videoByServerId[first.id] = CachedVideo(video, cachedAtMs = elapsedRealtimeMs())
                }
                Log.d(TAG, "warmed video ${channel.name} → ${video.source.take(80)}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "warm failed ${channel.name}: ${e.message}")
            } finally {
                inflight.remove(key, job)
            }
        }
        val previous = inflight.putIfAbsent(key, job)
        if (previous != null) {
            job.cancel()
            return
        }
        job.start()
    }

    private fun purgeStaleVideosLocked() {
        val now = elapsedRealtimeMs()
        val stale = videoByServerId.filterValues { !it.isFresh(now) }.keys.toList()
        stale.forEach { videoByServerId.remove(it) }
    }
}
