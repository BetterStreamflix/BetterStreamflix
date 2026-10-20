package com.dskja.betterstreamflix.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.activities.main.MainMobileActivity
import com.dskja.betterstreamflix.activities.main.MainTvActivity

/**
 * Opt-in product alerts (watchlist episodes, Live favorites starting).
 * Download completion stays on [com.dskja.betterstreamflix.download.DownloadNotifier].
 */
object ProductNotifier {
    const val CHANNEL_WATCHLIST = "watchlist_episodes"
    const val CHANNEL_LIVE = "live_alerts"

    private const val NOTIFICATION_ID_EPISODE_BASE = 43000
    /** Wide live id space to limit collisions across many favorites. */
    private const val NOTIFICATION_ID_LIVE_BASE = 500_000
    private const val NOTIFICATION_ID_LIVE_SLOTS = 100_000
    private const val NOTIFICATION_ID_EPISODE_SLOTS = 90

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WATCHLIST,
                context.getString(R.string.notify_channel_watchlist),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notify_channel_watchlist_desc)
                setShowBadge(true)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LIVE,
                context.getString(R.string.notify_channel_live),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notify_channel_live_desc)
                setShowBadge(true)
            },
        )
    }

    fun notifyNewEpisode(
        context: Context,
        showTitle: String,
        episodeLabel: String,
        identityKey: String,
    ) {
        if (!NotificationPermission.canPost(context)) return
        ensureChannels(context)
        val title = context.getString(R.string.notify_watchlist_episode_title)
        val body = context.getString(
            R.string.notify_watchlist_episode_body,
            showTitle,
            episodeLabel,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_WATCHLIST)
            .setSmallIcon(R.drawable.ic_favorite_enable)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openFavoritesIntent(context))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(episodeNotificationId(identityKey), notification)
        }
    }

    fun notifyLiveStarting(
        context: Context,
        showTitle: String,
        programHint: String?,
        identityKey: String,
        preKickoff: Boolean = false,
        kickoffEpochMs: Long? = null,
    ) {
        if (!NotificationPermission.canPost(context)) return
        ensureChannels(context)
        val title = if (preKickoff) {
            context.getString(R.string.notify_live_kickoff_title)
        } else {
            context.getString(R.string.notify_live_starting_title)
        }
        val body = when {
            preKickoff && !programHint.isNullOrBlank() ->
                context.getString(
                    R.string.notify_live_kickoff_body_program,
                    showTitle,
                    programHint,
                )
            preKickoff ->
                context.getString(R.string.notify_live_kickoff_body, showTitle)
            !programHint.isNullOrBlank() ->
                context.getString(R.string.notify_live_starting_body_program, showTitle, programHint)
            else ->
                context.getString(R.string.notify_live_starting_body, showTitle)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_menu_live)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openLiveIntent(context))
            .addAction(snoozeAction(context, identityKey, showTitle, kickoffEpochMs))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(liveNotificationId(identityKey), notification)
        }
    }

    /**
     * Settings → Notifications → "Send test notification".
     * Posts on the watchlist channel so delivery, tap-target and channel
     * sound/banner settings can be verified end-to-end.
     * Returns false when posting is blocked (permission / channel off).
     */
    fun sendTestNotification(context: Context): Boolean {
        if (!NotificationPermission.canPost(context)) return false
        ensureChannels(context)
        val body = context.getString(R.string.notify_test_body)
        val notification = NotificationCompat.Builder(context, CHANNEL_WATCHLIST)
            .setSmallIcon(R.drawable.ic_favorite_enable)
            .setContentTitle(context.getString(R.string.notify_test_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openFavoritesIntent(context))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID_EPISODE_BASE - 1, notification)
        }
        return true
    }

    fun liveNotificationId(identityKey: String): Int =
        NOTIFICATION_ID_LIVE_BASE + Math.floorMod(identityKey.hashCode(), NOTIFICATION_ID_LIVE_SLOTS)

    private fun episodeNotificationId(identityKey: String): Int =
        NOTIFICATION_ID_EPISODE_BASE + Math.floorMod(identityKey.hashCode(), NOTIFICATION_ID_EPISODE_SLOTS)

    private fun snoozeAction(
        context: Context,
        identityKey: String,
        showTitle: String,
        kickoffEpochMs: Long?,
    ): NotificationCompat.Action {
        val intent = Intent(context, LiveReminderSnoozeReceiver::class.java).apply {
            action = LiveReminderSnoozeReceiver.ACTION_SNOOZE
            putExtra(LiveReminderSnoozeReceiver.EXTRA_IDENTITY, identityKey)
            putExtra(LiveReminderSnoozeReceiver.EXTRA_TITLE, showTitle)
            if (kickoffEpochMs != null && kickoffEpochMs > 0L) {
                putExtra(LiveReminderSnoozeReceiver.EXTRA_KICKOFF, kickoffEpochMs)
            }
        }
        val pending = PendingIntent.getBroadcast(
            context,
            liveNotificationId(identityKey),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_menu_live,
            context.getString(R.string.notify_live_snooze),
            pending,
        ).build()
    }

    private fun openFavoritesIntent(context: Context): PendingIntent {
        val isTv = context.packageManager.hasSystemFeature("android.software.leanback")
        val intent = Intent(
            context,
            if (isTv) MainTvActivity::class.java else MainMobileActivity::class.java,
        ).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_favorites", true)
        }
        return PendingIntent.getActivity(
            context,
            43001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openLiveIntent(context: Context): PendingIntent {
        val isTv = context.packageManager.hasSystemFeature("android.software.leanback")
        val intent = Intent(
            context,
            if (isTv) MainTvActivity::class.java else MainMobileActivity::class.java,
        ).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_live", true)
        }
        return PendingIntent.getActivity(
            context,
            43002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
