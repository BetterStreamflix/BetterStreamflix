package com.dskja.betterstreamflix.iptv

import android.util.Log
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.providers.IptvProvider
import com.dskja.betterstreamflix.providers.Provider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Warms getServers (+ optional getVideo for the first server) for ±1 neighbors
 * so channel zapping skips a full cold resolve on the critical path.
 */
object IptvZapPreloader {

    private const val TAG = "IptvZapPreloader"
    private const val MAX_ENTRIES = 12
    private const val MAX_VIDEO_ENTRIES = 6
    /** Keep last-good m3u8 briefly so zap does not re-scrape immediately. */
    private const val VIDEO_TTL_MS = 90_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicInteger()
    private val serversByChannelId = ConcurrentHashMap<String, List<Video.Server>>()
    private val videoByServerId = ConcurrentHashMap<String, CachedVideo>()
    private val inflight = ConcurrentHashMap<String, Job>()

    private data class CachedVideo(
        val video: Video,
        val cachedAtMs: Long = System.currentTimeMillis(),
    ) {
        fun isFresh(now: Long = System.currentTimeMillis()): Boolean =
            now - cachedAtMs < VIDEO_TTL_MS
    }

    fun clear() {
        generation.incrementAndGet()
        inflight.values.forEach { it.cancel() }
        inflight.clear()
        serversByChannelId.clear()
        videoByServerId.clear()
    }

    fun takeCachedServers(channelId: String): List<Video.Server>? =
        serversByChannelId[channelId]?.takeIf { it.isNotEmpty() }

    fun peekCachedServers(channelId: String): List<Video.Server>? =
        serversByChannelId[channelId]?.takeIf { it.isNotEmpty() }

    fun takeCachedVideo(serverId: String): Video? {
        val hit = videoByServerId[serverId] ?: return null
        if (!hit.isFresh()) {
            videoByServerId.remove(serverId, hit)
            return null
        }
        return hit.video
    }

    fun warmNeighbors(provider: Provider, radius: Int = 1) {
        if (provider !is IptvProvider) return
        val neighbors = IptvLiveSession.neighbors(radius)
        if (neighbors.isEmpty()) return
        neighbors.forEach { channel ->
            warmChannel(provider, channel)
        }
    }

    fun warmChannel(provider: Provider, channel: IptvLiveSession.Channel) {
        if (provider !is IptvProvider) return
        if (serversByChannelId.containsKey(channel.id) &&
            serversByChannelId[channel.id]?.firstOrNull()?.let { videoByServerId.containsKey(it.id) } == true
        ) {
            return
        }
        if (inflight.containsKey(channel.id)) return
        val gen = generation.get()
        val job = scope.launch {
            try {
                val episode = IptvLiveSession.toEpisodeType(channel)
                val servers = provider.getServers(channel.id, episode)
                if (!isActive || gen != generation.get() || servers.isEmpty()) return@launch
                synchronized(serversByChannelId) {
                    if (gen != generation.get()) return@synchronized
                    trimIfNeeded()
                    serversByChannelId[channel.id] = servers
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
            }
        }
        val previous = inflight.putIfAbsent(channel.id, job)
        if (previous != null) {
            job.cancel()
            return
        }
        job.invokeOnCompletion { inflight.remove(channel.id, job) }
    }

    private fun trimIfNeeded() {
        if (serversByChannelId.size < MAX_ENTRIES) return
        val drop = serversByChannelId.keys.take(serversByChannelId.size - MAX_ENTRIES + 2)
        drop.forEach { serversByChannelId.remove(it) }
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
