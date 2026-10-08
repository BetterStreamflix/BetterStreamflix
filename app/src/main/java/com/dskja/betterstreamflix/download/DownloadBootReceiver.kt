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

/**
 * After reboot, warm the download manager and schedule a deferred resume.
 * Never start a dataSync foreground service from BOOT_COMPLETED — Android 12+
 * throws ForegroundServiceStartNotAllowedException (BETTERSTREAMFLIX-2P).
 */
class DownloadBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        runCatching {
            StreamflixDownloadManager.get(app)
        }.onFailure {
            Log.w(TAG, "Download manager warm failed: ${it.message}")
        }
        runCatching {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<DownloadResumeWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(
                DownloadResumeWorker.UNIQUE_WORK,
                ExistingWorkPolicy.KEEP,
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
