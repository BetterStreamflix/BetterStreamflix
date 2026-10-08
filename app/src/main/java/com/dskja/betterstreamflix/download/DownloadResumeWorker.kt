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
            StreamflixDownloadManager.get(applicationContext)
            // Soft-start: StreamflixDownloadService.start already catches
            // ForegroundServiceStartNotAllowedException / IllegalStateException.
            StreamflixDownloadService.start(applicationContext)
            Result.success()
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
