package com.dskja.betterstreamflix.download

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
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
    override fun onCreate() {
        // Beat the FGS timer before Media3 opens SimpleCache (BETTERSTREAMFLIX-2G).
        runCatching { promoteForegroundPlaceholder() }
        super.onCreate()
    }

    private fun promoteForegroundPlaceholder() {
        val notification = DownloadNotifier.buildForegroundNotification(
            context = this,
            title = getString(R.string.download_notification_active_one),
            progressPct = -1,
            indeterminate = true,
            contentText = getString(R.string.download_notification_indeterminate),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

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
        val etaSeconds = downloading?.let { d ->
            if (d.contentLength <= 0L || d.state != Download.STATE_DOWNLOADING) return@let -1L
            val remaining = (d.contentLength - d.bytesDownloaded).coerceAtLeast(0L)
            val history = com.dskja.betterstreamflix.net.NetworkThroughputHistory
                .estimateBytesPerSec()
            val instantaneous = DownloadEventBridge.lastSpeedBps(d.request.id)
            val honest = com.dskja.betterstreamflix.net.HonestEta.download(
                remainingBytes = remaining,
                instantaneousBps = instantaneous,
                historyBps = history,
                historySampleCount = com.dskja.betterstreamflix.net.NetworkThroughputHistory
                    .sampleCount(),
            )
            if (honest.confident && honest.speedBytesPerSec > 0L && remaining > 0L) {
                honest.etaSeconds
            } else {
                -1L
            }
        } ?: -1L
        val text = when {
            notMetRequirements != 0 -> getString(R.string.downloads_wifi_paused)
            indeterminate -> getString(R.string.download_notification_indeterminate)
            etaSeconds > 0L -> getString(
                R.string.download_notification_progress_eta,
                pct,
                DownloadSeriesFolders.formatEta(this, etaSeconds),
            )
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
         *
         * Warms [StreamflixDownloadManager] before [startForegroundService] so
         * SimpleCache/OpenHelper work is not on the FGS timer path.
         */
        fun start(context: Context): Boolean =
            tryStart(context) != StartResult.Failed

        fun tryStart(context: Context): StartResult {
            val app = context.applicationContext
            runCatching { StreamflixDownloadManager.get(app) }
                .onFailure {
                    android.util.Log.w(
                        "StreamflixDownloadService",
                        "Download manager warm before FGS failed: ${it.message}",
                    )
                }
            try {
                startForeground(app, StreamflixDownloadService::class.java)
                return StartResult.StartedForeground
            } catch (foregroundBlocked: Throwable) {
                // Includes ForegroundServiceStartNotAllowedException on API 31+
                // (BOOT_COMPLETED / background — BETTERSTREAMFLIX-2P / SS-02 / 2G).
                android.util.Log.w(
                    "StreamflixDownloadService",
                    "Foreground start blocked (${foregroundBlocked.javaClass.simpleName}: ${foregroundBlocked.message}); trying background start",
                )
                runCatching {
                    io.sentry.Sentry.addBreadcrumb(
                        io.sentry.Breadcrumb().apply {
                            category = "download.fgs"
                            message = "FGS start blocked: ${foregroundBlocked.javaClass.simpleName}"
                            level = io.sentry.SentryLevel.WARNING
                        },
                    )
                }
                return try {
                    start(app, StreamflixDownloadService::class.java)
                    StartResult.StartedBackground
                } catch (backgroundBlocked: Throwable) {
                    android.util.Log.w(
                        "StreamflixDownloadService",
                        "Background start also blocked: ${backgroundBlocked.message}",
                    )
                    runCatching {
                        io.sentry.Sentry.addBreadcrumb(
                            io.sentry.Breadcrumb().apply {
                                category = "download.fgs"
                                message = "FGS+bg start blocked: ${backgroundBlocked.javaClass.simpleName}"
                                level = io.sentry.SentryLevel.ERROR
                            },
                        )
                    }
                    runCatching { DownloadNotifier.notifyServiceStartFailed(app) }
                    StartResult.Failed
                }
            }
        }
    }

    enum class StartResult {
        StartedForeground,
        StartedBackground,
        Failed,
    }
}
