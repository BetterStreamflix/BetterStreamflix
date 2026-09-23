package com.dskja.betterstreamflix.iptv

import android.util.Log
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.providers.IptvProvider
import com.dskja.betterstreamflix.providers.Provider
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Warms getServers for ±1 neighbors so channel zapping skips a full cold resolve
 * on the critical path when the cache is still warm.
 */
object IptvZapPreloader {

    private const val TAG = "IptvZapPreloader"
    private const val MAX_ENTRIES = 12

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val serversByChannelId = ConcurrentHashMap<String, List<Video.Server>>()
    private val inflight = ConcurrentHashMap<String, Job>()

    fun clear() {
        inflight.values.forEach { it.cancel() }
        inflight.clear()
        serversByChannelId.clear()
    }

    fun takeCachedServers(channelId: String): List<Video.Server>? =
        serversByChannelId.remove(channelId)?.takeIf { it.isNotEmpty() }

    fun peekCachedServers(channelId: String): List<Video.Server>? =
        serversByChannelId[channelId]?.takeIf { it.isNotEmpty() }

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
        if (serversByChannelId.containsKey(channel.id)) return
        if (inflight.containsKey(channel.id)) return
        val job = scope.launch {
            mutex.withLock {
                if (serversByChannelId.containsKey(channel.id)) return@withLock
                runCatching {
                    val episode = IptvLiveSession.toEpisodeType(channel)
                    val servers = provider.getServers(channel.id, episode)
                    if (servers.isNotEmpty()) {
                        trimIfNeeded()
                        serversByChannelId[channel.id] = servers
                        Log.d(TAG, "warmed ${channel.name} (${servers.size} servers)")
                    }
                }.onFailure {
                    Log.d(TAG, "warm failed ${channel.name}: ${it.message}")
                }
            }
        }
        inflight[channel.id] = job
        job.invokeOnCompletion { inflight.remove(channel.id) }
    }

    private fun trimIfNeeded() {
        if (serversByChannelId.size < MAX_ENTRIES) return
        val drop = serversByChannelId.keys.take(serversByChannelId.size - MAX_ENTRIES + 2)
        drop.forEach { serversByChannelId.remove(it) }
    }
}
