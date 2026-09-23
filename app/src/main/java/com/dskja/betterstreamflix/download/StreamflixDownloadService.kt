package com.dskja.betterstreamflix.download

import android.app.Notification
import android.content.Context
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import com.dskja.betterstreamflix.R

class StreamflixDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    DownloadNotifier.CHANNEL_ID,
    R.string.download_notification_channel,
    R.string.download_notification_channel_desc,
) {
    override fun getDownloadManager(): DownloadManager =
        StreamflixDownloadManager.get(this)

    override fun getScheduler(): Scheduler? =
        if (Util.SDK_INT >= 21) PlatformScheduler(this, JOB_ID) else null

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int,
    ): Notification {
        // Single FGS notification with Pause/Resume — avoid a second aggregate
        // notification from DownloadNotifier (duplicate ongoing tiles).
        val active = downloads.filter {
            it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED
        }
        val downloading = active.firstOrNull { it.state == Download.STATE_DOWNLOADING }
            ?: active.firstOrNull()
            ?: downloads.firstOrNull()
        val pct = when {
            downloading == null -> -1
            downloading.percentDownloaded >= 0f ->
                downloading.percentDownloaded.toInt().coerceIn(0, 100)
            downloading.contentLength > 0L ->
                ((downloading.bytesDownloaded * 100L) / downloading.contentLength)
                    .toInt().coerceIn(0, 100)
            else -> -1
        }
        val indeterminate = pct < 0
        val title = when {
            active.size > 1 -> getString(R.string.download_notification_active_many, active.size)
            else -> getString(R.string.download_notification_active_one)
        }
        val text = when {
            notMetRequirements != 0 -> getString(R.string.downloads_wifi_paused)
            indeterminate -> getString(R.string.download_notification_indeterminate)
            else -> getString(R.string.download_notification_progress, pct)
        }
        return DownloadNotifier.buildForegroundNotification(
            context = this,
            title = title,
            progressPct = pct,
            indeterminate = indeterminate,
            contentText = text,
        )
    }

    companion object {
        const val FOREGROUND_NOTIFICATION_ID = 42001
        private const val JOB_ID = 4201

        /**
         * Prefer a foreground start (required while downloading). On Android 12+ a
         * background start may throw — fall back to a non-foreground start so the
         * job can still be scheduled; the next allowed context (notification tap /
         * Downloads tab) will promote it.
         */
        fun start(context: Context) {
            val app = context.applicationContext
            try {
                startForeground(app, StreamflixDownloadService::class.java)
            } catch (foregroundBlocked: IllegalStateException) {
                android.util.Log.w(
                    "StreamflixDownloadService",
                    "Foreground start blocked (${foregroundBlocked.message}); trying background start",
                )
                try {
                    start(app, StreamflixDownloadService::class.java)
                } catch (backgroundBlocked: Exception) {
                    android.util.Log.w(
                        "StreamflixDownloadService",
                        "Background start also blocked: ${backgroundBlocked.message}",
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w(
                    "StreamflixDownloadService",
                    "startForeground failed (${e.message}); trying background start",
                )
                runCatching { start(app, StreamflixDownloadService::class.java) }
                    .onFailure {
                        android.util.Log.w(
                            "StreamflixDownloadService",
                            "Unable to start download service: ${it.message}",
                        )
                    }
            }
        }
    }
}
