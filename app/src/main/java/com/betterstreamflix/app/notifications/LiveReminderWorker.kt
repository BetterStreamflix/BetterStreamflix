package com.betterstreamflix.app.notifications

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.betterstreamflix.app.utils.UserPreferences

/**
 * Fires a single pre-kickoff Live reminder scheduled by [LiveReminderScheduler].
 * Re-checks opt-in, kids/age gate, snooze, and flood gates before posting.
 */
class LiveReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!UserPreferences.notifyLiveFavoriteStarting) return Result.success()
        if (LiveReminderArm.deliveryBlocked()) return Result.success()
        if (!NotificationPermission.canPost(applicationContext)) return Result.success()

        val identityKey = inputData.getString(KEY_IDENTITY).orEmpty()
        val title = inputData.getString(KEY_TITLE).orEmpty()
        if (identityKey.isBlank() || title.isBlank()) return Result.success()

        // Still a tracked favorite / schedule — drop alerts after unfavorite.
        val stillRelevant = identityKey in UserPreferences.notifyLiveActiveKeys ||
            identityKey in UserPreferences.notifyLiveScheduledKeys
        if (!stillRelevant) return Result.success()

        val fireAt = inputData.getLong(KEY_FIRE_AT, -1L)
        val kickoffFromInput = inputData.getLong(KEY_KICKOFF, -1L)
        val kickoff = when {
            kickoffFromInput > 0L -> kickoffFromInput
            fireAt > 0L -> fireAt + LiveReminderPolicy.LEAD_BEFORE_KICKOFF_MS
            else -> return Result.success()
        }
        val snoozeWake = inputData.getBoolean(KEY_SNOOZE_WAKE, false)

        val now = System.currentTimeMillis()
        val pastWindow = now > kickoff + 2L * 60L * 1000L
        // Late Doze delivery of the original schedule: never fabricate now+LEAD.
        if (pastWindow && !snoozeWake) return Result.success()

        if (now >= LiveReminderStore.snoozedUntil(identityKey)) {
            LiveReminderStore.clearSnooze(identityKey)
        }

        val kickoffFp = kickoff.toString()
        val alreadyThisKickoff = !snoozeWake &&
            LiveReminderStore.kickoffFingerprint(identityKey) == kickoffFp

        val gate = LiveReminderPolicy.Gate(
            snoozedUntilMs = LiveReminderStore.snoozedUntil(identityKey),
            lastNotifiedMs = if (snoozeWake) 0L else LiveReminderStore.lastNotifiedMs(identityKey),
            recentFireTimesMs = LiveReminderStore.recentFires(),
            alreadyNotifiedThisKickoff = alreadyThisKickoff,
        )
        when (
            LiveReminderPolicy.decide(
                nowMs = now,
                kickoffEpochMs = kickoff,
                newlyLive = snoozeWake && pastWindow,
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
                    preKickoff = !pastWindow,
                    kickoffEpochMs = kickoff,
                )
                LiveReminderStore.markNotified(identityKey, now)
                LiveReminderStore.setKickoffFingerprint(identityKey, kickoffFp)
                LiveReminderScheduler.cancel(applicationContext, identityKey)
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
        const val KEY_KICKOFF = "kickoff"
        const val KEY_SNOOZE_WAKE = "snooze_wake"
    }
}
