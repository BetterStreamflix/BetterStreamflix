package com.dskja.betterstreamflix.download.ui

import com.dskja.betterstreamflix.download.DownloadErrorCode
import com.dskja.betterstreamflix.download.DownloadItemEntity
import com.dskja.betterstreamflix.download.DownloadItemState
import com.dskja.betterstreamflix.download.DownloadKind
import com.dskja.betterstreamflix.download.DownloadSeasonPackEntity
import com.dskja.betterstreamflix.download.DownloadStorage

sealed class DownloadRowUiModel {
    abstract val id: String

    data class Item(
        val entity: DownloadItemEntity,
    ) : DownloadRowUiModel() {
        override val id: String get() = entity.id
        val state: DownloadItemState get() = DownloadItemState.fromKey(entity.state)
        val isMovie: Boolean get() = entity.kind == DownloadKind.MOVIE.name
        val progressText: String
            get() = progressText(context = null)

        fun progressText(context: android.content.Context?): String {
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
            val used = DownloadStorage.formatBytes(context, entity.bytesDownloaded)
            val total = if (entity.contentLength > 0) {
                DownloadStorage.formatBytes(context, entity.contentLength)
            } else {
                "?"
            }
            val speed = if (entity.speedBytesPerSec > 0) {
                DownloadStorage.formatBytes(context, entity.speedBytesPerSec)
            } else {
                context?.getString(com.dskja.betterstreamflix.R.string.download_size_unknown) ?: "—"
            }
            val eta = if (entity.etaSeconds > 0) {
                val m = entity.etaSeconds / 60
                if (m > 0) "${m}m" else "${entity.etaSeconds}s"
            } else {
                context?.getString(com.dskja.betterstreamflix.R.string.download_size_unknown) ?: "—"
            }
            return "${entity.progressPct}% · $used / $total · $speed/s · ~$eta"
        }

        /** Localized failure line for the Downloads list (falls back to [progressText]). */
        fun failedSummary(context: android.content.Context): String {
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
