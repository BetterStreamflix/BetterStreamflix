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

    fun completedKeys(context: Context): StateFlow<Set<String>> {
        keysFlow?.let { return it.asStateFlow() }
        synchronized(this) {
            keysFlow?.let { return it.asStateFlow() }
            val appContext = context.applicationContext
            val flow = MutableStateFlow<Set<String>>(emptySet())
            keysFlow = flow
            scope.launch {
                // Seed from a one-shot DB read so first paint is not stuck on emptySet.
                runCatching {
                    flow.value = DownloadRepository.get(appContext).completedKeys()
                }
                DownloadRepository.get(appContext)
                    .observeCompletedKeys()
                    .collect { flow.value = it }
            }
            return flow.asStateFlow()
        }
    }

    fun isCompleted(context: Context, contentKey: String): Boolean =
        completedKeys(context).value.contains(contentKey)

    /** Drop cached flow after download DB / profile resets so the next read re-seeds. */
    fun reset() {
        synchronized(this) {
            keysFlow = null
        }
    }
}
