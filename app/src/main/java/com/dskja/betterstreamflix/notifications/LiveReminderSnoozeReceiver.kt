package com.dskja.betterstreamflix.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * Notification action: snooze this Live favorite reminder for 15 minutes.
 */
class LiveReminderSnoozeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_SNOOZE) return
        val key = intent.getStringExtra(EXTRA_IDENTITY).orEmpty()
        if (key.isBlank()) return
        val until = LiveReminderPolicy.snoozeUntil(System.currentTimeMillis())
        LiveReminderStore.setSnooze(key, until)
        LiveReminderScheduler.cancel(context, key)
        runCatching {
            NotificationManagerCompat.from(context.applicationContext)
                .cancel(ProductNotifier.liveNotificationId(key))
        }
        Log.i(TAG, "snoozed $key until $until")
    }

    companion object {
        private const val TAG = "LiveReminderSnooze"
        const val ACTION_SNOOZE = "com.dskja.betterstreamflix.notifications.LIVE_REMINDER_SNOOZE"
        const val EXTRA_IDENTITY = "identity_key"
    }
}
