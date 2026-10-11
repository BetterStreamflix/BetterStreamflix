package com.dskja.betterstreamflix.notifications

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dskja.betterstreamflix.utils.UserPreferences

/**
 * Fires a single pre-kickoff Live reminder scheduled by [LiveReminderScheduler].
 * Re-checks opt-in, snooze, and flood gates before posting.
 */
class LiveReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!UserPreferences.notifyLiveFavoriteStarting) return Result.success()
        if (!NotificationPermission.canPost(applicationContext)) return Result.success()

        val identityKey = inputData.getString(KEY_IDENTITY).orEmpty()
        val title = inputData.getString(KEY_TITLE).orEmpty()
        if (identityKey.isBlank() || title.isBlank()) return Result.success()

        val now = System.currentTimeMillis()
        val gate = LiveReminderPolicy.Gate(
            snoozedUntilMs = LiveReminderStore.snoozedUntil(identityKey),
            lastNotifiedMs = LiveReminderStore.lastNotifiedMs(identityKey),
            recentFireTimesMs = LiveReminderStore.recentFires(),
            alreadyNotifiedThisKickoff = false,
        )
        when (
            LiveReminderPolicy.decide(
                nowMs = now,
                kickoffEpochMs = now + LiveReminderPolicy.LEAD_BEFORE_KICKOFF_MS,
                newlyLive = false,
                gate = gate,
            )
        ) {
            is LiveReminderPolicy.Decision.NotifyNow -> {
                ProductNotifier.ensureChannels(applicationContext)
                ProductNotifier.notifyLiveStarting(
                    context = applicationContext,
                    showTitle = title,
                    programHint = null,
                    identityKey = identityKey,
                    preKickoff = true,
                )
                LiveReminderStore.markNotified(identityKey, now)
                LiveReminderStore.setKickoffFingerprint(identityKey, "fired:$now")
                Log.i(TAG, "pre-kickoff: $title")
            }
            else -> Unit
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "LiveReminderWorker"
        const val KEY_IDENTITY = "identity"
        const val KEY_TITLE = "title"
        const val KEY_FIRE_AT = "fire_at"
    }
}
