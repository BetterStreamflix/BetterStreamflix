package com.betterstreamflix.app.notifications

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.betterstreamflix.app.utils.UserPreferences

/**
 * Background pass for opt-in Live + watchlist alerts. Respects system permission
 * and hard caps so we never spam.
 */
class NotificationCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!NotificationCheckScheduler.anyOptInEnabled()) {
            NotificationCheckScheduler.sync(applicationContext)
            return Result.success()
        }
        if (!NotificationPermission.canPost(applicationContext)) {
            Log.d(TAG, "skip: system notifications disabled or permission missing")
            return Result.success()
        }
        ProductNotifier.ensureChannels(applicationContext)

        // Live reminders are capped harder inside LiveFavoriteStartingChecker
        // (1/run, 2/hour, snooze) so they never flood beside watchlist alerts.
        var budget = MAX_NOTIFICATIONS_PER_RUN
        runCatching {
            if (UserPreferences.notifyLiveFavoriteStarting && budget > 0) {
                budget -= LiveFavoriteStartingChecker.check(applicationContext, budget)
            }
        }.onFailure {
            Log.w(TAG, "live check failed: ${it.message}")
        }
        runCatching {
            if (UserPreferences.notifyWatchlistNewEpisode && budget > 0) {
                val now = System.currentTimeMillis()
                val last = UserPreferences.notifyEpisodeLastCheckAtMillis
                if (last == 0L || now - last >= EPISODE_CHECK_INTERVAL_MS) {
                    UserPreferences.notifyEpisodeLastCheckAtMillis = now
                    budget -= WatchlistEpisodeChecker.check(applicationContext, budget)
                }
            }
        }.onFailure {
            Log.w(TAG, "episode check failed: ${it.message}")
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "NotifyCheckWorker"
        private const val MAX_NOTIFICATIONS_PER_RUN = 3
        private const val EPISODE_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L
    }
}
