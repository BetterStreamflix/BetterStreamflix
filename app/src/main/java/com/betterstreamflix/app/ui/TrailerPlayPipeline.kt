package com.betterstreamflix.app.ui

import android.os.SystemClock
import android.util.Log
import com.betterstreamflix.app.models.Video
import com.betterstreamflix.app.providers.IptvProvider
import com.betterstreamflix.app.providers.ProviderSmoke
import com.betterstreamflix.app.providers.TmdbProvider
import com.betterstreamflix.app.utils.UserPreferences
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Trailer → Play pipeline: warm VOD [Provider.getServers] (+ first [getVideo]) while a
 * trailer plays so “Jetzt streamen” / Watch Now opens the player on a hot cache.
 *
 * Separate from [com.betterstreamflix.app.iptv.IptvZapPreloader] (live zap only).
 */
object TrailerPlayPipeline {

    private const val TAG = "TrailerPlayPipeline"
    private const val MAX_ENTRIES = 8
    private const val MAX_VIDEO_ENTRIES = 8
    /** Server lists for VOD stay useful for a few minutes after trailer end. */
    private const val TTL_MS = 180_000L
    private const val VIDEO_TTL_MS = 120_000L

    data class Target(
        val contentId: String,
        val videoType: Video.Type,
    )

    /** Active trailer session: warm target + stream CTA action (detail / dialog). */
    data class Session(
        val target: Target?,
        val onStreamNow: (() -> Unit)?,
    )

    @Volatile
    var activeSession: Session? = null
        private set

    /** Overridable for JVM unit tests (SystemClock is not mocked there). */
    @Volatile
    internal var elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() }

    private data class CacheEntry(
        val servers: List<Video.Server>,
        val storedAtMs: Long,
        val providerName: String,
    )

    private data class CachedVideo(
        val video: Video,
        val cachedAtMs: Long,
    ) {
        fun isFresh(now: Long = elapsedRealtimeMs()): Boolean =
            now - cachedAtMs < VIDEO_TTL_MS
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicInteger()
    private val serversByContentId =
        object : LinkedHashMap<String, CacheEntry>(8, 0.75f, /* accessOrder = */ true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
                size > MAX_ENTRIES
        }
    private val videoByServerId =
        object : LinkedHashMap<String, CachedVideo>(8, 0.75f, /* accessOrder = */ true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedVideo>?): Boolean =
                size > MAX_VIDEO_ENTRIES
        }
    private val inflight = ConcurrentHashMap<String, Job>()

    fun beginSession(session: Session?) {
        activeSession = session
        session?.target?.let { warm(it) }
    }

    fun endSession() {
        activeSession = null
    }

    fun clear() {
        generation.incrementAndGet()
        inflight.values.forEach { it.cancel() }
        inflight.clear()
        synchronized(serversByContentId) { serversByContentId.clear() }
        synchronized(videoByServerId) { videoByServerId.clear() }
        activeSession = null
    }

    fun warm(target: Target) {
        val contentId = canonicalizeId(target.contentId)
        if (contentId.isEmpty()) return
        val provider = currentProviderOrNull() ?: return
        if (provider is IptvProvider) return // Live uses IptvZapPreloader.
        if (peekCachedServers(contentId) != null) return

        val gen = generation.get()
        // Atomic put-if-absent: concurrent Stream Now + dialog warm share one job.
        val created = arrayOfNulls<Job>(1)
        inflight.compute(contentId) { _, existing ->
            if (existing?.isActive == true) {
                existing
            } else {
                scope.launch {
                    try {
                        val timeoutMs = serversTimeoutMs(provider)
                        val servers = ProviderSmoke.withProviderTimeout(
                            timeoutMs = timeoutMs,
                            label = "trailerWarm.getServers(${provider.name})",
                        ) {
                            provider.getServers(contentId, target.videoType)
                        }
                        if (gen != generation.get() || !isActive || servers.isEmpty()) return@launch
                        synchronized(serversByContentId) {
                            serversByContentId[contentId] = CacheEntry(
                                servers = servers,
                                storedAtMs = elapsedRealtimeMs(),
                                providerName = provider.name,
                            )
                        }
                        Log.d(TAG, "warmed $contentId (${servers.size} servers via ${provider.name})")
                        val first = servers.firstOrNull() ?: return@launch
                        if (peekCachedVideo(first.id) != null) return@launch
                        runCatching {
                            val video = ProviderSmoke.withProviderTimeout(
                                timeoutMs = ProviderSmoke.SERVERS_TIMEOUT_MS,
                                label = "trailerWarm.getVideo(${first.name})",
                            ) {
                                provider.getVideo(first)
                            }
                            if (gen != generation.get() || !isActive) return@runCatching
                            if (video.source.isBlank()) return@runCatching
                            synchronized(videoByServerId) {
                                videoByServerId[first.id] = CachedVideo(video, elapsedRealtimeMs())
                            }
                            Log.d(TAG, "warmed first video for $contentId / ${first.name}")
                        }.onFailure { e ->
                            if (e is CancellationException) throw e
                            Log.d(TAG, "first-video warm skipped for $contentId: ${e.message}")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.d(TAG, "warm failed for $contentId: ${e.message}")
                    } finally {
                        val self = created[0]
                        if (self != null) inflight.remove(contentId, self)
                    }
                }.also { created[0] = it }
            }
        }
    }

    fun peekCachedServers(contentId: String): List<Video.Server>? {
        val id = canonicalizeId(contentId)
        val providerName = currentProviderOrNull()?.name
        synchronized(serversByContentId) {
            val entry = serversByContentId[id] ?: return null
            if (elapsedRealtimeMs() - entry.storedAtMs > TTL_MS) {
                serversByContentId.remove(id)
                return null
            }
            if (providerName != null && entry.providerName != providerName) {
                serversByContentId.remove(id)
                return null
            }
            return entry.servers.takeIf { it.isNotEmpty() }
        }
    }

    /** True when a non-empty server list is already warm for this content. */
    fun isServersHot(contentId: String): Boolean =
        !peekCachedServers(contentId).isNullOrEmpty()

    /**
     * Short wait for an in-flight warm job so Stream Now can open on a hot
     * server. Returns true when servers are available (already hot or finished
     * within [timeoutMs]). Never throws on timeout.
     */
    suspend fun awaitServersHot(contentId: String, timeoutMs: Long): Boolean {
        val id = canonicalizeId(contentId)
        if (id.isEmpty()) return false
        if (isServersHot(id)) return true
        // withTimeout uses real time (not [elapsedRealtimeMs]) so JVM tests with a
        // frozen clock still complete; also covers the warm() put-if-absent race.
        return try {
            withTimeout(timeoutMs.coerceAtLeast(0L)) {
                while (isActive) {
                    if (isServersHot(id)) return@withTimeout true
                    val job = inflight[id]
                    if (job != null) {
                        job.join()
                        return@withTimeout isServersHot(id)
                    }
                    delay(16L)
                }
                false
            }
        } catch (_: TimeoutCancellationException) {
            isServersHot(id)
        }
    }

    internal fun canonicalizeId(contentId: String): String =
        contentId.trim().lowercase(Locale.US)

    private fun currentProviderOrNull(): com.betterstreamflix.app.providers.Provider? =
        runCatching { UserPreferences.currentProvider }.getOrNull()

    fun peekCachedVideo(serverId: String): Video? {
        synchronized(videoByServerId) {
            val entry = videoByServerId[serverId] ?: return null
            if (!entry.isFresh()) {
                videoByServerId.remove(serverId)
                return null
            }
            return entry.video
        }
    }

    fun invalidate(contentId: String) {
        val id = canonicalizeId(contentId)
        val servers = synchronized(serversByContentId) {
            serversByContentId.remove(id)?.servers
        }
        if (servers != null) {
            synchronized(videoByServerId) {
                servers.forEach { videoByServerId.remove(it.id) }
            }
        }
        inflight.remove(id)?.cancel()
    }

    /** Test harness: seed warm caches without launching network work. */
    internal fun seedForTest(
        contentId: String,
        servers: List<Video.Server>,
        providerName: String = "Test",
        video: Video? = null,
    ) {
        val id = canonicalizeId(contentId)
        val now = elapsedRealtimeMs()
        synchronized(serversByContentId) {
            serversByContentId[id] = CacheEntry(servers, now, providerName)
        }
        val firstId = servers.firstOrNull()?.id
        if (video != null && firstId != null) {
            synchronized(videoByServerId) {
                videoByServerId[firstId] = CachedVideo(video, now)
            }
        }
    }

    private fun serversTimeoutMs(provider: com.betterstreamflix.app.providers.Provider): Long =
        when (provider) {
            is TmdbProvider ->
                if (provider.language.lowercase(Locale.US).startsWith("de")) {
                    ProviderSmoke.SERVERS_TIMEOUT_DE_MS
                } else {
                    ProviderSmoke.SERVERS_TIMEOUT_MS
                }
            else -> ProviderSmoke.SERVERS_TIMEOUT_MS
        }
}
