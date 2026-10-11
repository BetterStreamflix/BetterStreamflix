package com.dskja.betterstreamflix.download

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Deferred download resume after BOOT_COMPLETED / cold start. Starts the download
 * service from a WorkManager worker context (allowed), never from the boot receiver.
 */
class DownloadResumeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            // Idle-check here (not in the boot receiver) so BOOT_COMPLETED never
            // blocks on Room (SS-01). FAILED-only queues are user/notification driven.
            val hasActive = runCatching {
                DownloadRepository.get(applicationContext).getAllOnce().any { item ->
                    val state = DownloadItemState.fromKey(item.state)
                    state.isActive || state == DownloadItemState.QUEUED
                }
            }.getOrDefault(false)
            if (!hasActive) return Result.success()

            // Warm SimpleCache / DownloadManager on the worker executor before FGS
            // so StreamflixDownloadService.onCreate is not racing the FGS timer.
            StreamflixDownloadManager.get(applicationContext)
            when (StreamflixDownloadService.tryStart(applicationContext)) {
                StreamflixDownloadService.StartResult.StartedForeground -> Result.success()
                StreamflixDownloadService.StartResult.StartedBackground -> {
                    // Background-only start may not run the queue on Android 12+.
                    // Retry while active items remain so WM can promote later.
                    val stillActive = withContext(Dispatchers.IO) {
                        DownloadRepository.get(applicationContext).getAllOnce().any {
                            val state = DownloadItemState.fromKey(it.state)
                            state.isActive || state == DownloadItemState.QUEUED
                        }
                    }
                    if (stillActive) Result.retry() else Result.success()
                }
                StreamflixDownloadService.StartResult.Failed -> Result.retry()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Resume worker failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK = "betterstreamflix_download_resume"
        private const val TAG = "DownloadResumeWorker"
    }
}
