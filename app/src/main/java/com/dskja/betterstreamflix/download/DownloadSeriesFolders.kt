package com.dskja.betterstreamflix.download

import android.content.Context
import com.dskja.betterstreamflix.R
import org.json.JSONObject
import java.io.File

/**
 * Per-series folders under the downloads root for episode sidecars / progressive
 * materialization, plus helpers to group Downloads-tab rows by show.
 *
 * Media3 adaptive cache stays in [DownloadStorage.cacheDir]; this organizes
 * human-visible series trees beside it.
 */
object DownloadSeriesFolders {
    private const val SERIES_ROOT = "series"
    private const val MOVIES_ROOT = "movies"

    data class SeriesRef(
        val key: String,
        val title: String,
        val posterUrl: String = "",
    )

    fun sanitizeFolderName(raw: String): String {
        val trimmed = raw.trim().ifBlank { "Unknown" }
        val cleaned = trimmed
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .take(80)
            .trim()
            .trimEnd('.')
        return cleaned.ifBlank { "Unknown" }
    }

    fun seriesRoot(context: Context): File =
        File(DownloadStorage.downloadsDir(context), SERIES_ROOT).also { ensureDir(it) }

    fun moviesRoot(context: Context): File =
        File(DownloadStorage.downloadsDir(context), MOVIES_ROOT).also { ensureDir(it) }

    /**
     * Ensure `downloads/series/<ShowTitle>/` exists for an episode download.
     * Returns the folder, or null for movies / missing metadata.
     */
    fun ensureForItem(context: Context, item: DownloadItemEntity): File? {
        val ref = seriesRef(item) ?: run {
            if (item.kind == DownloadKind.MOVIE.name) {
                return ensureDir(moviesRoot(context))
            }
            return null
        }
        val folder = File(seriesRoot(context), sanitizeFolderName(ref.title))
        return ensureDir(folder)
    }

    fun seriesRef(item: DownloadItemEntity): SeriesRef? {
        if (item.kind != DownloadKind.EPISODE.name) return null
        val fromJson = runCatching {
            val o = JSONObject(item.videoTypeJson.ifBlank { "{}" })
            val showId = o.optString("showId").trim()
            val showTitle = o.optString("showTitle").trim()
            if (showId.isBlank() && showTitle.isBlank()) null
            else SeriesRef(
                key = "series|${item.providerName}|${showId.ifBlank { showTitle }}",
                title = showTitle.ifBlank { item.title },
                posterUrl = o.optString("showPoster").ifBlank { item.posterUrl },
            )
        }.getOrNull()
        if (fromJson != null) return fromJson

        // contentKey: episode|provider|tvShowId|season|ep|episodeId
        val parts = item.contentKey.split('|')
        if (parts.size >= 3 && parts[0] == "episode") {
            val showId = parts[2]
            // Prefer show poster from videoTypeJson; never fall back to episode art (DL-P3-H).
            val showPoster = runCatching {
                JSONObject(item.videoTypeJson.ifBlank { "{}" }).optString("showPoster")
            }.getOrNull().orEmpty()
            return SeriesRef(
                key = "series|${item.providerName}|$showId",
                title = item.title.ifBlank { showId },
                posterUrl = showPoster,
            )
        }
        return null
    }

    fun formatEta(context: Context?, etaSeconds: Long): String {
        if (etaSeconds <= 0L) {
            return context?.getString(R.string.downloads_eta_unknown)
                ?: context?.getString(R.string.download_size_unknown)
                ?: "…"
        }
        val total = etaSeconds.toInt().coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return when {
            hours > 0 && minutes > 0 ->
                context?.getString(R.string.downloads_eta_hours_minutes, hours, minutes)
                    ?: "${hours}h ${minutes}m"
            hours > 0 ->
                context?.getString(R.string.downloads_eta_hours, hours) ?: "${hours}h"
            minutes > 0 && seconds > 0 ->
                context?.getString(R.string.downloads_eta_minutes_seconds, minutes, seconds)
                    ?: "${minutes}m ${seconds}s"
            minutes > 0 ->
                context?.getString(R.string.downloads_eta_minutes, minutes) ?: "${minutes}m"
            else ->
                context?.getString(R.string.downloads_eta_seconds, seconds) ?: "${seconds}s"
        }
    }

    fun needsRedownload(item: DownloadItemEntity): Boolean =
        item.state == DownloadItemState.FAILED.name &&
            item.errorCode == DownloadErrorCode.STORAGE_RELOCATED.name

    private fun ensureDir(dir: File): File? {
        return try {
            if (!dir.exists() && !dir.mkdirs()) return null
            if (!dir.isDirectory) return null
            dir
        } catch (_: Exception) {
            null
        }
    }
}
