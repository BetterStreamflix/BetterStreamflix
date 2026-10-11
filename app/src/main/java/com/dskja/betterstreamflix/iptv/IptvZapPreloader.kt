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

    private data class CacheEntry(
        val servers: List<Video.Server>,
        val storedAtMs: Long = SystemClock.elapsedRealtime(),
    )

    private data class CachedVideo(
        val video: Video,
        val cachedAtMs: Long = System.currentTimeMillis(),
    ) {
        fun isFresh(now: Long = System.currentTimeMillis()): Boolean =
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
    private val videoByServerId = ConcurrentHashMap<String, CachedVideo>()
    private val inflight = ConcurrentHashMap<String, Job>()

    fun clear() {
        generation.incrementAndGet()
        inflight.values.forEach { it.cancel() }
        inflight.clear()
        synchronized(serversByChannelId) {
            serversByChannelId.clear()
        }
        videoByServerId.clear()
    }

    /** Consume a warm entry (remove-on-take). Expired entries are dropped. */
    fun takeCachedServers(channelId: String): List<Video.Server>? {
        synchronized(serversByChannelId) {
            val entry = serversByChannelId.remove(channelId) ?: return null
            if (SystemClock.elapsedRealtime() - entry.storedAtMs > TTL_MS) return null
            return entry.servers.takeIf { it.isNotEmpty() }
        }
    }

    fun peekCachedServers(channelId: String): List<Video.Server>? {
        synchronized(serversByChannelId) {
            val entry = serversByChannelId[channelId] ?: return null
            if (SystemClock.elapsedRealtime() - entry.storedAtMs > TTL_MS) {
                serversByChannelId.remove(channelId)
                return null
            }
            return entry.servers.takeIf { it.isNotEmpty() }
        }
    }

    fun invalidate(channelId: String) {
        synchronized(serversByChannelId) {
            serversByChannelId.remove(channelId)
        }
        inflight.remove(channelId)?.cancel()
    }

    fun takeCachedVideo(serverId: String): Video? {
        val hit = videoByServerId[serverId] ?: return null
        if (!hit.isFresh()) {
            videoByServerId.remove(serverId, hit)
            return null
        }
        videoByServerId.remove(serverId, hit)
        return hit.video
    }

    /** Non-destructive warm hit — preferred on the zap critical path. */
    fun peekCachedVideo(serverId: String): Video? {
        val hit = videoByServerId[serverId] ?: return null
        if (!hit.isFresh()) {
            videoByServerId.remove(serverId, hit)
            return null
        }
        return hit.video
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
        if (serversHit != null &&
            serversHit.firstOrNull()?.let { videoByServerId.containsKey(it.id) } == true
        ) {
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
                    serversByChannelId[channel.id] = CacheEntry(servers)
                }
                Log.d(TAG, "warmed ${channel.name} (${servers.size} servers)")

                // Also warm getVideo for the first server (±1) so zap skips HTML scrape.
                val first = servers.firstOrNull() ?: return@launch
                if (videoByServerId.containsKey(first.id)) return@launch
                val video = runCatching { provider.getVideo(first) }.getOrNull() ?: return@launch
                if (!isActive || gen != generation.get()) return@launch
                if (video.source.isBlank()) return@launch
                synchronized(videoByServerId) {
                    if (gen != generation.get()) return@synchronized
                    trimVideosIfNeeded()
                    videoByServerId[first.id] = CachedVideo(video)
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

    private fun trimVideosIfNeeded() {
        // Drop stale first.
        val now = System.currentTimeMillis()
        val stale = videoByServerId.filterValues { !it.isFresh(now) }.keys
        stale.forEach { videoByServerId.remove(it) }
        if (videoByServerId.size < MAX_VIDEO_ENTRIES) return
        val drop = videoByServerId.keys.take(videoByServerId.size - MAX_VIDEO_ENTRIES + 2)
        drop.forEach { videoByServerId.remove(it) }
    }
}
