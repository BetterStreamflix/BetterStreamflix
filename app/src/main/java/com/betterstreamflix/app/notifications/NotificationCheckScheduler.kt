package com.betterstreamflix.app.notifications

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.betterstreamflix.app.utils.UserPreferences
import java.util.concurrent.TimeUnit

/**
 * Periodic opt-in notification checks. Cancelled when every toggle is off.
 */
object NotificationCheckScheduler {
    private const val TAG = "NotifyScheduler"
    const val UNIQUE_WORK = "opt_in_product_notifications"

    fun anyOptInEnabled(): Boolean =
        UserPreferences.notifyWatchlistNewEpisode ||
            UserPreferences.notifyLiveFavoriteStarting

    fun sync(context: Context) {
        val app = context.applicationContext
        if (!anyOptInEnabled()) {
            runCatching {
                WorkManager.getInstance(app).cancelUniqueWork(UNIQUE_WORK)
            }.onFailure {
                Log.w(TAG, "cancel failed: ${it.message}")
            }
            return
        }
        runCatching {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            // Android minimum period is 15 minutes; keep checks light + anti-spam inside worker.
            val request = PeriodicWorkRequestBuilder<NotificationCheckWorker>(
                15,
                TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }.onFailure {
            Log.w(TAG, "enqueue failed: ${it.message}")
        }
    }

    /** Force a sooner pass after the user opts in (does not replace the periodic schedule). */
    fun enqueueImmediate(context: Context) {
        if (!anyOptInEnabled()) return
        runCatching {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = androidx.work.OneTimeWorkRequestBuilder<NotificationCheckWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueue(request)
        }.onFailure {
            Log.w(TAG, "immediate enqueue failed: ${it.message}")
        }
    }
}
