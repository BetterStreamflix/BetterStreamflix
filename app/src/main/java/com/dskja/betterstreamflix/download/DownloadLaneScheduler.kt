package com.dskja.betterstreamflix.download

import android.content.Context
import android.util.Log
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Applies [DownloadLanePolicy] to the live Media3 queue: pause lower lanes with
 * [DownloadQueuePolicy.STOP_LANE], resume lane-holds when slots free up.
 */
object DownloadLaneScheduler {
    private const val TAG = "DownloadLane"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun applyAsync(context: Context) {
        val app = context.applicationContext
        scope.launch { apply(app) }
    }

    suspend fun apply(context: Context) = mutex.withLock {
        if (DownloadConnectivityMonitor.shouldHoldForWifiOnly(context)) return
        if (DownloadStorage.isLowSpace(context)) return

        val repo = DownloadRepository.get(context)
        val slots = UserPreferences.downloadMaxConcurrent.coerceIn(1, 4)
        val items = repo.getAllOnce().filter { DownloadProfileScope.matchesActive(it.profileId) }
        val plan = DownloadLanePolicy.plan(items, slots, DownloadLaneFocus.current())
        if (plan.runIds.isEmpty() && plan.holdIds.isEmpty()) return

        for (id in plan.holdIds) {
            val item = items.find { it.id == id } ?: continue
            val state = DownloadItemState.fromKey(item.state)
            if (state == DownloadItemState.PAUSED && DownloadLanePolicy.isLaneHeld(item)) continue
            if (state != DownloadItemState.QUEUED &&
                state != DownloadItemState.PREPARING &&
                state != DownloadItemState.DOWNLOADING
            ) {
                continue
            }
            runCatching {
                repo.pause(id, DownloadQueuePolicy.STOP_LANE, foreground = false)
            }.onFailure { Log.w(TAG, "lane hold failed for $id: ${it.message}") }
        }

        for (id in plan.runIds) {
            val item = items.find { it.id == id } ?: continue
            if (!DownloadLanePolicy.isLaneHeld(item)) continue
            runCatching {
                repo.resume(id, foreground = false)
            }.onFailure { Log.w(TAG, "lane resume failed for $id: ${it.message}") }
        }
    }
}
