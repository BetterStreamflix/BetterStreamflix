package com.dskja.betterstreamflix.notifications

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.LiveHubCatalog
import com.dskja.betterstreamflix.utils.UserPreferences
import java.util.concurrent.TimeUnit

/**
 * Schedules exact “5 minutes before kickoff” OneTime work for Live Hub favorites
 * that expose a parseable clock time. Cancels stale work when favorites change.
 */
object LiveReminderScheduler {
    private const val TAG = "LiveReminderSched"
    private const val WORK_PREFIX = "live_reminder_"

    fun uniqueWorkName(identityKey: String): String =
        WORK_PREFIX + identityKey.hashCode().toUInt().toString(16)

    /**
     * Called from Live Hub after Favorites resolve — schedules upcoming kickoffs
     * without posting notifications (worker / checker still own delivery).
     */
    fun syncFromFavorites(context: Context, favorites: List<TvShow>) {
        if (!UserPreferences.notifyLiveFavoriteStarting) {
            cancelAllTracked(context)
            return
        }
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val keep = HashSet<String>()
        for (show in favorites) {
            val key = LiveHubCatalog.identityKey(show)
            val kickoff = LiveReminderPolicy.parseKickoffEpochMs(
                title = show.title,
                overview = show.overview,
                nowMs = now,
            ) ?: continue
            val fireAt = kickoff - LiveReminderPolicy.LEAD_BEFORE_KICKOFF_MS
            if (fireAt <= now) continue
            if (LiveReminderStore.snoozedUntil(key) > now) continue
            keep += key
            schedule(app, key, show.title, fireAt, now)
        }
        // Drop schedules for favorites that disappeared or lost kickoff times.
        pruneMissing(app, keep)
    }

    fun schedule(
        context: Context,
        identityKey: String,
        title: String,
        fireAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val delay = (fireAtMs - nowMs).coerceAtLeast(0L)
        if (delay <= 0L) return
        runCatching {
            val data = workDataOf(
                LiveReminderWorker.KEY_IDENTITY to identityKey,
                LiveReminderWorker.KEY_TITLE to title,
                LiveReminderWorker.KEY_FIRE_AT to fireAtMs,
            )
            val request = OneTimeWorkRequestBuilder<LiveReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build(),
                )
                .setInputData(data)
                .addTag(WORK_PREFIX)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                uniqueWorkName(identityKey),
                ExistingWorkPolicy.REPLACE,
                request,
            )
            track(identityKey)
            Log.d(TAG, "scheduled $identityKey in ${delay / 1000}s")
        }.onFailure {
            Log.w(TAG, "schedule failed: ${it.message}")
        }
    }

    fun cancel(context: Context, identityKey: String) {
        runCatching {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(uniqueWorkName(identityKey))
        }
        untrack(identityKey)
    }

    private fun cancelAllTracked(context: Context) {
        val keys = UserPreferences.notifyLiveScheduledKeys
        keys.forEach { cancel(context, it) }
        UserPreferences.notifyLiveScheduledKeys = emptySet()
    }

    private fun pruneMissing(context: Context, keep: Set<String>) {
        val tracked = UserPreferences.notifyLiveScheduledKeys
        tracked.filter { it !in keep }.forEach { cancel(context, it) }
        UserPreferences.notifyLiveScheduledKeys = keep
    }

    private fun track(identityKey: String) {
        UserPreferences.notifyLiveScheduledKeys =
            UserPreferences.notifyLiveScheduledKeys + identityKey
    }

    private fun untrack(identityKey: String) {
        UserPreferences.notifyLiveScheduledKeys =
            UserPreferences.notifyLiveScheduledKeys - identityKey
    }
}
