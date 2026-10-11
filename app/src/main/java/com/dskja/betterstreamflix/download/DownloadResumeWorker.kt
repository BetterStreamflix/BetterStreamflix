package com.dskja.betterstreamflix.download

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Deferred download resume after BOOT_COMPLETED. Starts the download service
 * from a WorkManager worker context (allowed), never from the boot receiver.
 */
class DownloadResumeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            // Idle-check here (not in the boot receiver) so BOOT_COMPLETED never
            // blocks on Room (SS-01).
            val hasPending = runCatching {
                DownloadRepository.get(applicationContext).getAllOnce().any { item ->
                    val state = DownloadItemState.fromKey(item.state)
                    state.isActive ||
                        state == DownloadItemState.FAILED ||
                        state == DownloadItemState.QUEUED
                }
            }.getOrDefault(false)
            if (!hasPending) return Result.success()

            // Warm SimpleCache / DownloadManager on the worker executor before FGS
            // so StreamflixDownloadService.onCreate is not racing the FGS timer.
            StreamflixDownloadManager.get(applicationContext)
            // Soft-start: StreamflixDownloadService.start already catches
            // ForegroundServiceStartNotAllowedException / IllegalStateException
            // and warms the manager again if needed.
            if (!StreamflixDownloadService.start(applicationContext)) {
                Result.retry()
            } else {
                Result.success()
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
