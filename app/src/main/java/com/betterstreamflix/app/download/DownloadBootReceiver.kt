package com.betterstreamflix.app.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * After reboot, schedule a deferred resume via WorkManager.
 * Never start a dataSync foreground service from BOOT_COMPLETED — Android 12+
 * throws ForegroundServiceStartNotAllowedException (BETTERSTREAMFLIX-2P).
 * Never warm [StreamflixDownloadManager] on the boot main thread (SimpleCache I/O).
 * Never block the boot main thread with Room (SS-01) — enqueue unconditionally;
 * [DownloadResumeWorker] idle-checks before starting FGS.
 */
class DownloadBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        // BOOT_COMPLETED is not ordered; treat ordered deliveries as spoof.
        if (isOrderedBroadcast) {
            Log.w(TAG, "Ignoring ordered BOOT_COMPLETED spoof")
            return
        }
        // Explicit intents from other apps often carry extras; system boot usually does not.
        if (intent.extras != null && !intent.extras!!.isEmpty) {
            Log.w(TAG, "Ignoring BOOT_COMPLETED with extras (likely spoof)")
            return
        }
        // No Room probe here — worker idle-checks on its executor.
        DownloadResumeScheduler.enqueue(
            context.applicationContext,
            replace = true,
            probeRoom = false,
        )
    }

    private companion object {
        const val TAG = "DownloadBootReceiver"
    }
}
