package com.dskja.betterstreamflix.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * Notification action: snooze this Live favorite reminder for 15 minutes.
 * Cancels the current work, then schedules a wake at snooze-until.
 */
class LiveReminderSnoozeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_SNOOZE) return
        val key = intent.getStringExtra(EXTRA_IDENTITY).orEmpty()
        if (key.isBlank()) return
        val now = System.currentTimeMillis()
        val until = LiveReminderPolicy.snoozeUntil(now)
        LiveReminderStore.setSnooze(key, until)
        LiveReminderScheduler.cancel(context, key)
        runCatching {
            NotificationManagerCompat.from(context.applicationContext)
                .cancel(ProductNotifier.liveNotificationId(key))
        }

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { key }
        val kickoff = intent.getLongExtra(EXTRA_KICKOFF, -1L).takeIf { it > 0L }
            ?: LiveReminderStore.kickoffFingerprint(key)?.toLongOrNull()
            ?: (until + LiveReminderPolicy.LEAD_BEFORE_KICKOFF_MS)
        LiveReminderScheduler.schedule(
            context = context.applicationContext,
            identityKey = key,
            title = title,
            fireAtMs = until,
            nowMs = now,
            kickoffEpochMs = kickoff,
            snoozeWake = true,
        )
        Log.i(TAG, "snoozed $key until $until")
    }

    companion object {
        private const val TAG = "LiveReminderSnooze"
        const val ACTION_SNOOZE = "com.dskja.betterstreamflix.notifications.LIVE_REMINDER_SNOOZE"
        const val EXTRA_IDENTITY = "identity_key"
        const val EXTRA_TITLE = "title"
        const val EXTRA_KICKOFF = "kickoff"
    }
}
