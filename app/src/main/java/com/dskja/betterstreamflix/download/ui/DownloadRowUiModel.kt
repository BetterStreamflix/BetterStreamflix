package com.dskja.betterstreamflix.download.ui

import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.download.DownloadErrorCode
import com.dskja.betterstreamflix.download.DownloadItemEntity
import com.dskja.betterstreamflix.download.DownloadItemState
import com.dskja.betterstreamflix.download.DownloadKind
import com.dskja.betterstreamflix.download.DownloadSeasonPackEntity
import com.dskja.betterstreamflix.download.DownloadSeriesFolders
import com.dskja.betterstreamflix.download.DownloadStorage

sealed class DownloadRowUiModel {
    abstract val id: String

    data class Item(
        val entity: DownloadItemEntity,
    ) : DownloadRowUiModel() {
        override val id: String get() = entity.id
        val state: DownloadItemState get() = DownloadItemState.fromKey(entity.state)
        val isMovie: Boolean get() = entity.kind == DownloadKind.MOVIE.name
        val needsRedownload: Boolean get() = DownloadSeriesFolders.needsRedownload(entity)
        val progressText: String
            get() = progressText(context = null)

        fun progressText(context: android.content.Context?): String {
            if (needsRedownload) {
                return context?.getString(R.string.downloads_redownload_needed)
                    ?: "Re-download needed"
            }
            if (state == DownloadItemState.FAILED) {
                val code = runCatching {
                    DownloadErrorCode.valueOf(entity.errorCode.orEmpty())
                }.getOrNull()
                val msg = entity.errorMessage?.takeIf { it.isNotBlank() }.orEmpty()
                return when {
                    code != null && code != DownloadErrorCode.UNKNOWN ->
                        "Failed · ${code.name.lowercase().replace('_', ' ')}" +
                            if (msg.isNotBlank()) " · $msg" else ""
                    msg.isNotBlank() -> "Failed · $msg"
                    else -> "Failed"
                }
            }
            if (state == DownloadItemState.COMPLETED) {
                val sizeBytes = maxOf(entity.contentLength, entity.bytesDownloaded)
                val size = if (sizeBytes > 0L) {
                    DownloadStorage.formatBytes(context, sizeBytes)
                } else {
                    context?.getString(R.string.download_size_unknown) ?: "—"
                }
                return context?.getString(R.string.downloads_completed_size, size) ?: size
            }
            val wifiHeld = state == DownloadItemState.PAUSED &&
                entity.errorCode == DownloadErrorCode.WIFI_REQUIRED.name
            val storageHeld = state == DownloadItemState.PAUSED &&
                entity.errorCode == DownloadErrorCode.LOW_STORAGE.name
            val laneHeld = state == DownloadItemState.PAUSED &&
                entity.errorCode == DownloadErrorCode.LANE_HOLD.name
            if (wifiHeld || storageHeld || laneHeld) {
                val used = DownloadStorage.formatBytes(context, entity.bytesDownloaded)
                val total = if (entity.contentLength > 0) {
                    DownloadStorage.formatBytes(context, entity.contentLength)
                } else {
                    context?.getString(R.string.download_size_unknown) ?: "?"
                }
                return if (context != null) {
                    context.getString(
                        when {
                            storageHeld -> R.string.downloads_progress_storage_held
                            laneHeld -> R.string.downloads_progress_lane_held
                            else -> R.string.downloads_progress_wifi_held
                        },
                        entity.progressPct,
                        used,
                        total,
                    )
                } else if (storageHeld) {
                    "${entity.progressPct}% · $used / $total · Low storage"
                } else if (laneHeld) {
                    "${entity.progressPct}% · $used / $total · Waiting for higher priority"
                } else {
                    "${entity.progressPct}% · $used / $total · Wi‑Fi required"
                }
            }
            val used = DownloadStorage.formatBytes(context, entity.bytesDownloaded)
            val knownTotal = entity.contentLength > 0
            val total = if (knownTotal) {
                DownloadStorage.formatBytes(context, entity.contentLength)
            } else {
                context?.getString(R.string.download_size_unknown) ?: "?"
            }
            val speed = if (entity.speedBytesPerSec > 0) {
                DownloadStorage.formatBytes(context, entity.speedBytesPerSec)
            } else {
                context?.getString(R.string.download_size_unknown) ?: "—"
            }
            val eta = DownloadSeriesFolders.formatEta(context, entity.etaSeconds)
            // Unknown total or stalled speed: simpler line without optimistic ETA (DL-P3-E/F).
            val useSimple = !knownTotal || entity.speedBytesPerSec <= 0L || entity.etaSeconds < 0L
            return if (context != null) {
                if (useSimple) {
                    context.getString(
                        R.string.downloads_progress_line_simple,
                        entity.progressPct,
                        used,
                    )
                } else {
                    context.getString(
                        R.string.downloads_progress_line,
                        entity.progressPct,
                        used,
                        total,
                        speed,
                        eta,
                    )
                }
            } else if (useSimple) {
                "${entity.progressPct}% · $used"
            } else {
                "${entity.progressPct}% · $used / $total · $speed/s · ~$eta"
            }
        }

        /** Localized failure line for the Downloads list (falls back to [progressText]). */
        fun failedSummary(context: android.content.Context): String {
            if (needsRedownload) {
                return context.getString(R.string.downloads_redownload_needed)
            }
            if (state != DownloadItemState.FAILED) return progressText(context)
            val code = runCatching {
                DownloadErrorCode.valueOf(entity.errorCode.orEmpty())
            }.getOrNull() ?: DownloadErrorCode.UNKNOWN
            return DownloadOptionsController.localizedError(
                context,
                code,
                entity.errorMessage.orEmpty(),
            )
        }
    }

    data class SeasonPack(
        val pack: DownloadSeasonPackEntity,
    ) : DownloadRowUiModel() {
        override val id: String get() = pack.id
        val state: DownloadItemState get() = DownloadItemState.fromKey(pack.state)
    }

    /** Collapsible series folder header grouping episode rows underneath. */
    data class SeriesFolder(
        val seriesKey: String,
        val title: String,
        val episodeCount: Int,
        val totalBytes: Long,
        val posterUrl: String = "",
        val expanded: Boolean = true,
    ) : DownloadRowUiModel() {
        override val id: String get() = "series_$seriesKey"
        fun subtitle(context: android.content.Context): String {
            val size = if (totalBytes > 0L) {
                DownloadStorage.formatBytes(context, totalBytes)
            } else {
                context.getString(R.string.download_size_unknown)
            }
            return context.getString(
                R.string.downloads_series_folder_subtitle,
                episodeCount,
                size,
            )
        }
    }

    data class Header(val title: String) : DownloadRowUiModel() {
        override val id: String get() = "header_$title"
    }
}

enum class DownloadsFilter {
    ALL,
    DOWNLOADING,
    COMPLETED,
    FAILED,
}

enum class DownloadsSort {
    NEWEST,
    TITLE,
    SIZE,
}
