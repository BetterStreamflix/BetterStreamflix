package com.dskja.betterstreamflix.download

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Deferred download resume after BOOT_COMPLETED / cold start. Starts the download
 * service only when the process is foreground-eligible — never force a dataSync
 * FGS from a boot-associated worker (BETTERSTREAMFLIX-2P).
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

            // Android 12+ bans dataSync FGS from BOOT_COMPLETED / restricted bg.
            // Warm cache only and retry until the user opens the app.
            if (!isForegroundEligible(applicationContext)) {
                runCatching { StreamflixDownloadManager.get(applicationContext) }
                Log.i(TAG, "Deferring FGS resume until process is foreground-eligible")
                return Result.retry()
            }

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

    private fun isForegroundEligible(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        val proc = am.runningAppProcesses?.firstOrNull {
            it.processName == context.packageName
        } ?: return false
        return proc.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    companion object {
        const val UNIQUE_WORK = "betterstreamflix_download_resume"
        private const val TAG = "DownloadResumeWorker"
    }
}
