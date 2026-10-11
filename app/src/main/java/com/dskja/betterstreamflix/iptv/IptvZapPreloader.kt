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
    /** Access-order LRU; guarded by itself. */
    private val serversByChannelId =
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

    fun clear() {
        generation.incrementAndGet()
        inflight.values.forEach { it.cancel() }
        inflight.clear()
        synchronized(serversByChannelId) {
            serversByChannelId.clear()
        }
        synchronized(videoByServerId) {
            videoByServerId.clear()
        }
    }

    /** Consume a warm entry (remove-on-take). Expired entries are dropped. */
    fun takeCachedServers(channelId: String): List<Video.Server>? {
        synchronized(serversByChannelId) {
            val entry = serversByChannelId.remove(channelId) ?: return null
            if (elapsedRealtimeMs() - entry.storedAtMs > TTL_MS) return null
            return entry.servers.takeIf { it.isNotEmpty() }
        }
    }

    fun peekCachedServers(channelId: String): List<Video.Server>? {
        synchronized(serversByChannelId) {
            val entry = serversByChannelId[channelId] ?: return null
            if (elapsedRealtimeMs() - entry.storedAtMs > TTL_MS) {
                serversByChannelId.remove(channelId)
                return null
            }
            return entry.servers.takeIf { it.isNotEmpty() }
        }
    }

    fun invalidate(channelId: String) {
        val servers = synchronized(serversByChannelId) {
            serversByChannelId.remove(channelId)?.servers
        }
        // Also drop warmed m3u8 for this channel — otherwise peek re-serves dead CDN URLs ≤ VIDEO_TTL.
        if (servers != null) {
            synchronized(videoByServerId) {
                servers.forEach { server -> videoByServerId.remove(server.id) }
            }
        }
        inflight.remove(channelId)?.cancel()
    }

    /** Test harness: seed warm caches without launching network work. */
    internal fun seedForTest(channelId: String, servers: List<Video.Server>, video: Video? = null) {
        val now = elapsedRealtimeMs()
        synchronized(serversByChannelId) {
            serversByChannelId[channelId] = CacheEntry(servers, storedAtMs = now)
        }
        val firstId = servers.firstOrNull()?.id
        if (video != null && firstId != null) {
            synchronized(videoByServerId) {
                videoByServerId[firstId] = CachedVideo(video, cachedAtMs = now)
            }
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
        val neighbors = IptvLiveSession.neighbors(radius)
        if (neighbors.isEmpty()) return
        neighbors.forEach { channel ->
            warmChannel(provider, channel)
        }
    }

    fun warmChannel(provider: Provider, channel: IptvLiveSession.Channel) {
        if (provider !is IptvProvider) return
        val serversHit = peekCachedServers(channel.id)
        val firstServerId = serversHit?.firstOrNull()?.id
        val freshVideo = firstServerId?.let { peekCachedVideo(it) }
        if (serversHit != null && freshVideo != null) {
            return
        }
        if (inflight.containsKey(channel.id)) return
        val gen = generation.get()
        // Register inflight before launch so a concurrent warm cannot race.
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val episode = IptvLiveSession.toEpisodeType(channel)
                val servers = provider.getServers(channel.id, episode)
                if (!isActive || gen != generation.get() || servers.isEmpty()) return@launch
                synchronized(serversByChannelId) {
                    if (gen != generation.get()) return@synchronized
                    serversByChannelId[channel.id] = CacheEntry(servers, storedAtMs = elapsedRealtimeMs())
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
                inflight.remove(channel.id, job)
            }
        }
        val previous = inflight.putIfAbsent(channel.id, job)
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
