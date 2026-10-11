package com.dskja.betterstreamflix.sync

import android.content.Context
import android.util.Log
import com.dskja.betterstreamflix.models.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * Host heartbeat + guest poll for Watch-Party light.
 * Syncs position + server name/id only (no stream relay).
 */
object WatchPartySync {
    private const val TAG = "WatchPartySync"
    private const val HOST_INTERVAL_MS = 1_500L
    private const val GUEST_POLL_MS = 1_500L
    /** Ignore tiny drifts so seek loops do not fight local buffering. */
    const val SEEK_THRESHOLD_MS = 2_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()

    private var hostJob: Job? = null
    private var guestJob: Job? = null
    private var lastHostPublishAt = 0L
    private var lastHostPosition = -1L
    private var lastHostPlaying: Boolean? = null
    private var lastHostServerKey: String? = null

    fun interface GuestListener {
        fun onUpdate(payload: WatchPartyPayload)
    }

    fun interface EndedListener {
        fun onEnded()
    }

    suspend fun stop() {
        lifecycleMutex.withLock {
            hostJob?.cancelAndJoin()
            hostJob = null
            guestJob?.cancelAndJoin()
            guestJob = null
            lastHostPublishAt = 0L
            lastHostPosition = -1L
            lastHostPlaying = null
            lastHostServerKey = null
        }
    }

    fun startHost(
        context: Context,
        partyId: String,
        snapshot: () -> HostSnapshot?,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            lifecycleMutex.withLock {
                hostJob?.cancelAndJoin()
                hostJob = scope.launch {
                    while (isActive) {
                        val snap = snapshot()
                        if (snap != null) {
                            maybePublishHost(appContext, partyId, snap)
                        }
                        delay(HOST_INTERVAL_MS)
                    }
                }
            }
        }
    }

    /** Force an immediate host publish (pause / seek / server change). */
    fun publishHostNow(
        context: Context,
        partyId: String,
        snapshot: HostSnapshot,
    ) {
        scope.launch {
            runCatching {
                WatchPartyRepository.heartbeat(
                    context = context.applicationContext,
                    partyId = partyId,
                    server = snapshot.server,
                    positionMs = snapshot.positionMs,
                    playing = snapshot.playing,
                )
                lastHostPublishAt = System.currentTimeMillis()
                lastHostPosition = snapshot.positionMs
                lastHostPlaying = snapshot.playing
                lastHostServerKey = serverKey(snapshot.server)
            }.onFailure { Log.w(TAG, "Host publish failed", it) }
        }
    }

    fun startGuest(
        context: Context,
        token: String,
        listener: GuestListener,
        onEnded: EndedListener,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            lifecycleMutex.withLock {
                guestJob?.cancelAndJoin()
                guestJob = scope.launch {
                    while (isActive) {
                        val result = runCatching {
                            WatchPartyRepository.fetchByToken(appContext, token)
                        }
                        result.onSuccess { payload ->
                            if (payload.ended) {
                                onEnded.onEnded()
                                return@launch
                            }
                            listener.onUpdate(payload)
                        }.onFailure { error ->
                            val msg = error.message.orEmpty()
                            if (msg.contains("not found", ignoreCase = true) ||
                                msg.contains("ended", ignoreCase = true)
                            ) {
                                onEnded.onEnded()
                                return@launch
                            }
                            Log.w(TAG, "Guest poll failed", error)
                        }
                        delay(GUEST_POLL_MS)
                    }
                }
            }
        }
    }

    fun shouldSeek(localMs: Long, remoteMs: Long): Boolean =
        abs(localMs - remoteMs) > SEEK_THRESHOLD_MS

    fun serverKey(server: Video.Server?): String? {
        if (server == null) return null
        return "${server.id}|${server.name}"
    }

    fun serverKey(id: String?, name: String?): String? {
        if (id.isNullOrBlank() && name.isNullOrBlank()) return null
        return "${id.orEmpty()}|${name.orEmpty()}"
    }

    data class HostSnapshot(
        val positionMs: Long,
        val playing: Boolean,
        val server: Video.Server?,
    )

    private suspend fun maybePublishHost(
        context: Context,
        partyId: String,
        snap: HostSnapshot,
    ) {
        val now = System.currentTimeMillis()
        val key = serverKey(snap.server)
        val force = snap.playing != lastHostPlaying ||
            key != lastHostServerKey ||
            abs(snap.positionMs - lastHostPosition) > SEEK_THRESHOLD_MS
        if (!force && now - lastHostPublishAt < HOST_INTERVAL_MS) return

        runCatching {
            WatchPartyRepository.heartbeat(
                context = context,
                partyId = partyId,
                server = snap.server,
                positionMs = snap.positionMs,
                playing = snap.playing,
            )
            lastHostPublishAt = now
            lastHostPosition = snap.positionMs
            lastHostPlaying = snap.playing
            lastHostServerKey = key
        }.onFailure { Log.w(TAG, "Host heartbeat failed", it) }
    }
}
