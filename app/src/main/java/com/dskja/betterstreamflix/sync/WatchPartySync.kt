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
 * Host heartbeat + guest poll for Watch-Party 2.0.
 * Syncs position + server name/id only (no stream relay).
 * Lobby / chat use separate poll loops while the party is open.
 */
/**
 * Poll intervals (~1.5s host/guest) intentionally trade exact frame sync for simplicity.
 * Claim/scrub skew up to one interval is expected (CC-063). Realtime publication exists
 * in SQL but this client stays poll-only (CC-064).
 */
object WatchPartySync {
    private const val TAG = "WatchPartySync"
    private const val HOST_INTERVAL_MS = 1_500L
    private const val GUEST_POLL_MS = 1_500L
    private const val LOBBY_POLL_MS = 1_500L
    /** Ignore tiny drifts so seek loops do not fight local buffering. */
    const val SEEK_THRESHOLD_MS = 2_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()

    private var hostJob: Job? = null
    private var guestJob: Job? = null
    private var lobbyJob: Job? = null
    private var chatJob: Job? = null
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

    fun interface LobbyListener {
        fun onLobby(lobby: WatchPartyLobbyPayload, messages: List<WatchPartyMessagePayload>)
    }

    suspend fun stop() {
        lifecycleMutex.withLock {
            hostJob?.cancelAndJoin()
            hostJob = null
            guestJob?.cancelAndJoin()
            guestJob = null
            lobbyJob?.cancelAndJoin()
            lobbyJob = null
            chatJob?.cancelAndJoin()
            chatJob = null
            lastHostPublishAt = 0L
            lastHostPosition = -1L
            lastHostPlaying = null
            lastHostServerKey = null
        }
    }

    /** Stop host heartbeat only (player teardown); keep lobby/session alive. */
    suspend fun stopHostOnly() {
        lifecycleMutex.withLock {
            hostJob?.cancelAndJoin()
            hostJob = null
            lastHostPublishAt = 0L
            lastHostPosition = -1L
            lastHostPlaying = null
            lastHostServerKey = null
        }
    }

    suspend fun stopLobbyOnly() {
        lifecycleMutex.withLock {
            lobbyJob?.cancelAndJoin()
            lobbyJob = null
        }
    }

    suspend fun stopChatOnly() {
        lifecycleMutex.withLock {
            chatJob?.cancelAndJoin()
            chatJob = null
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
            if (WatchTogetherPro.shouldSkipHeartbeatForSidekick(
                    WatchPartySession.lastSeenSidekickSeekSeq,
                    WatchPartySession.lastAppliedSeekSeq,
                )
            ) {
                return@launch
            }
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

    fun startLobby(
        context: Context,
        token: String,
        listener: LobbyListener,
        onEnded: EndedListener,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            lifecycleMutex.withLock {
                lobbyJob?.cancelAndJoin()
                lobbyJob = scope.launch {
                    while (isActive) {
                        if (!pollLobbyOnce(appContext, token, listener, onEnded)) return@launch
                        delay(LOBBY_POLL_MS)
                    }
                }
            }
        }
    }

    /** In-player chat panel — separate from lobby so dismiss does not kill lobby polling. */
    fun startChat(
        context: Context,
        token: String,
        listener: LobbyListener,
        onEnded: EndedListener,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            lifecycleMutex.withLock {
                chatJob?.cancelAndJoin()
                chatJob = scope.launch {
                    while (isActive) {
                        if (!pollLobbyOnce(appContext, token, listener, onEnded)) return@launch
                        delay(LOBBY_POLL_MS)
                    }
                }
            }
        }
    }

    /** @return false when the poll loop should stop (ended / not found). */
    private suspend fun pollLobbyOnce(
        appContext: Context,
        token: String,
        listener: LobbyListener,
        onEnded: EndedListener,
    ): Boolean {
        var keepGoing = true
        runCatching {
            WatchPartyRepository.heartbeatMember(appContext, token)
            val lobby = WatchPartyRepository.fetchLobby(appContext, token)
            if (lobby.party.ended) {
                onEnded.onEnded()
                keepGoing = false
                return@runCatching
            }
            val after = WatchPartySession.lastMessageAt
            val messages = WatchPartyRepository.listMessages(
                appContext,
                token,
                after,
            )
            if (messages.isNotEmpty()) {
                WatchPartySession.lastMessageAt =
                    messages.last().createdAt ?: WatchPartySession.lastMessageAt
            }
            listener.onLobby(lobby, messages)
        }.onFailure { error ->
            val msg = error.message.orEmpty()
            if (msg.contains("not found", ignoreCase = true) ||
                msg.contains("ended", ignoreCase = true)
            ) {
                onEnded.onEnded()
                keepGoing = false
            } else {
                Log.w(TAG, "Lobby/chat poll failed", error)
            }
        }
        return keepGoing
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
        if (WatchTogetherPro.shouldSkipHeartbeatForSidekick(
                WatchPartySession.lastSeenSidekickSeekSeq,
                WatchPartySession.lastAppliedSeekSeq,
            )
        ) {
            return
        }
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
