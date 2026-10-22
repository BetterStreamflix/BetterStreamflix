package com.betterstreamflix.app.download

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.betterstreamflix.app.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * Shared enqueue for [DownloadResumeWorker] (boot, cold start, wifi-toggle).
 */
object DownloadResumeScheduler {
    private const val TAG = "DownloadResumeScheduler"

    /**
     * Schedule a deferred download-service start.
     *
     * @param probeRoom when true (cold start / wifi toggle), skip enqueue if Room
     * has no active/queued work. Boot must pass false so the main thread never
     * blocks on Room — the worker idle-checks instead.
     */
    fun enqueue(
        context: Context,
        replace: Boolean = false,
        probeRoom: Boolean = true,
    ) {
        val app = context.applicationContext
        if (probeRoom) {
            val hasActive = runCatching {
                runBlocking(Dispatchers.IO) {
                    DownloadRepository.get(app).getAllOnce().any { item ->
                        val state = DownloadItemState.fromKey(item.state)
                        state.isActive || state == DownloadItemState.QUEUED
                    }
                }
            }.getOrDefault(false)
            if (!hasActive) return
        }

        runCatching {
            val networkType = if (UserPreferences.downloadWifiOnly) {
                NetworkType.UNMETERED
            } else {
                NetworkType.CONNECTED
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .build()
            val request = OneTimeWorkRequestBuilder<DownloadResumeWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(
                DownloadResumeWorker.UNIQUE_WORK,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }.onFailure {
            Log.w(TAG, "Could not schedule download resume: ${it.message}")
        }
    }
}
