package com.dskja.betterstreamflix.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * After reboot, schedule a deferred resume via WorkManager.
 * Never start a dataSync foreground service from BOOT_COMPLETED — Android 12+
 * throws ForegroundServiceStartNotAllowedException (BETTERSTREAMFLIX-2P).
 * Never warm [StreamflixDownloadManager] on the boot main thread (SimpleCache I/O).
 *
 * Keep exported for BOOT_COMPLETED, but idle-return before scheduling work when
 * Room has no pending/failed/queued downloads (minimize spoofed-broadcast work).
 */
class DownloadBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        // Cheap Room probe only — do not warm StreamflixDownloadManager while idle.
        val hasPending = runCatching {
            runBlocking(Dispatchers.IO) {
                DownloadRepository.get(app).getAllOnce().any { item ->
                    val state = DownloadItemState.fromKey(item.state)
                    state.isActive ||
                        state == DownloadItemState.FAILED ||
                        state == DownloadItemState.QUEUED
                }
            }
        }.getOrDefault(false)
        if (!hasPending) return

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
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(
                DownloadResumeWorker.UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }.onFailure {
            Log.w(TAG, "Could not schedule download resume: ${it.message}")
        }
    }

    private companion object {
        const val TAG = "DownloadBootReceiver"
    }
}
