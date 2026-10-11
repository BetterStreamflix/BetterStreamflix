package com.dskja.betterstreamflix.download

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object OfflineBadgeStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var keysFlow: MutableStateFlow<Set<String>>? = null

    @Volatile
    private var activeKeysFlow: MutableStateFlow<Set<String>>? = null

    fun completedKeys(context: Context): StateFlow<Set<String>> {
        ensureWarm(context)
        return keysFlow!!.asStateFlow()
    }

    /** Content keys that are queued / preparing / downloading / paused. */
    fun activeKeys(context: Context): StateFlow<Set<String>> {
        ensureWarm(context)
        return activeKeysFlow!!.asStateFlow()
    }

    fun isCompleted(context: Context, contentKey: String): Boolean =
        completedKeys(context).value.contains(contentKey)

    fun isActive(context: Context, contentKey: String): Boolean =
        activeKeys(context).value.contains(contentKey)

    private fun ensureWarm(context: Context) {
        if (keysFlow != null && activeKeysFlow != null) return
        synchronized(this) {
            if (keysFlow != null && activeKeysFlow != null) return
            val appContext = context.applicationContext
            val completed = keysFlow ?: MutableStateFlow<Set<String>>(emptySet()).also {
                keysFlow = it
            }
            val active = activeKeysFlow ?: MutableStateFlow<Set<String>>(emptySet()).also {
                activeKeysFlow = it
            }
            scope.launch {
                // Seed from a one-shot DB read so first paint is not stuck on emptySet.
                runCatching {
                    completed.value = DownloadRepository.get(appContext).completedKeys()
                    active.value = DownloadRepository.get(appContext).getAllOnce()
                        .filter { DownloadItemState.fromKey(it.state).isActive }
                        .map { it.contentKey }
                        .toSet()
                }
                launch {
                    DownloadRepository.get(appContext)
                        .observeCompletedKeys()
                        .collect { completed.value = it }
                }
                launch {
                    DownloadRepository.get(appContext).observeAll().collect { items ->
                        active.value = items
                            .filter { DownloadItemState.fromKey(it.state).isActive }
                            .map { it.contentKey }
                            .toSet()
                    }
                }
            }
        }
    }

    /** Drop cached flow after download DB / profile resets so the next read re-seeds. */
    fun reset() {
        synchronized(this) {
            keysFlow = null
            activeKeysFlow = null
        }
    }
}
