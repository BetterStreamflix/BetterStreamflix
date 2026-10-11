package com.dskja.betterstreamflix.sync

import android.content.Context
import android.util.Log
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

/**
 * Host heartbeat + shared poll for Cast Room queue control.
 * Queue mutations are token RPCs; TV applies now-playing changes locally.
 */
object CastRoomSync {
    private const val TAG = "CastRoomSync"
    private const val HOST_INTERVAL_MS = 1_500L
    private const val POLL_MS = 1_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()

    private var hostJob: Job? = null
    private var pollJob: Job? = null

    fun interface RoomListener {
        fun onUpdate(payload: CastRoomPayload)
    }

    fun interface EndedListener {
        fun onEnded()
    }

    data class HostSnapshot(
        val nowPlayingIndex: Int,
        val positionMs: Long,
        val playing: Boolean,
    )

    suspend fun stop() {
        lifecycleMutex.withLock {
            hostJob?.cancelAndJoin()
            hostJob = null
            pollJob?.cancelAndJoin()
            pollJob = null
        }
    }

    fun startHostHeartbeat(
        context: Context,
        roomId: String,
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
                            runCatching {
                                CastRoomRepository.hostHeartbeat(
                                    context = appContext,
                                    roomId = roomId,
                                    nowPlayingIndex = snap.nowPlayingIndex,
                                    positionMs = snap.positionMs,
                                    playing = snap.playing,
                                )
                            }.onFailure { Log.w(TAG, "Host heartbeat failed", it) }
                        }
                        delay(HOST_INTERVAL_MS)
                    }
                }
            }
        }
    }

    fun startPolling(
        context: Context,
        token: String,
        listener: RoomListener,
        onEnded: EndedListener,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            lifecycleMutex.withLock {
                pollJob?.cancelAndJoin()
                pollJob = scope.launch {
                    while (isActive) {
                        val result = runCatching {
                            CastRoomRepository.fetchByToken(appContext, token)
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
                            Log.w(TAG, "Cast Room poll failed", error)
                        }
                        delay(POLL_MS)
                    }
                }
            }
        }
    }
}
