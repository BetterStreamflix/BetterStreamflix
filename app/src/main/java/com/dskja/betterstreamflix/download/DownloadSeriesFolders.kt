package com.dskja.betterstreamflix.download

import android.content.Context
import com.dskja.betterstreamflix.R
import org.json.JSONObject
import java.io.File
import java.text.NumberFormat
import java.util.Locale

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
                    ?: localeEta(hours, "h", minutes, "m")
            hours > 0 ->
                context?.getString(R.string.downloads_eta_hours, hours) ?: localeEta(hours, "h")
            minutes > 0 && seconds > 0 ->
                context?.getString(R.string.downloads_eta_minutes_seconds, minutes, seconds)
                    ?: localeEta(minutes, "m", seconds, "s")
            minutes > 0 ->
                context?.getString(R.string.downloads_eta_minutes, minutes) ?: localeEta(minutes, "m")
            else ->
                context?.getString(R.string.downloads_eta_seconds, seconds) ?: localeEta(seconds, "s")
        }
    }

    /** Locale-aware numeric fallback when [formatEta] has no [Context] (OS-040). */
    private fun localeEta(a: Int, aUnit: String, b: Int? = null, bUnit: String? = null): String {
        val nf = NumberFormat.getIntegerInstance(Locale.getDefault())
        return if (b != null && bUnit != null) {
            "${nf.format(a)}$aUnit ${nf.format(b)}$bUnit"
        } else {
            "${nf.format(a)}$aUnit"
        }
    }

    /**
     * Failed rows that need a full re-download (cache/file gone), not a simple network retry.
     * Includes [DownloadErrorCode.STORAGE_RELOCATED] and [DownloadErrorCode.FILE_MISSING].
     * Profile-agnostic / no-Context path — does not probe COMPLETED playability.
     */
    fun needsRedownload(item: DownloadItemEntity): Boolean {
        if (item.state != DownloadItemState.FAILED.name) return false
        return item.errorCode == DownloadErrorCode.STORAGE_RELOCATED.name ||
            item.errorCode == DownloadErrorCode.FILE_MISSING.name
    }

    /**
     * Health probe: FAILED+FILE_MISSING/STORAGE_RELOCATED when unplayable, plus COMPLETED
     * rows that fail [OfflinePlayback.canPlayOffline] so Care fills before FAILED reconcile (OS-019).
     */
    fun needsRedownload(context: android.content.Context, item: DownloadItemEntity): Boolean {
        if (needsRedownload(item)) {
            val playable = runCatching {
                OfflinePlayback.canPlayOffline(context, item)
            }.getOrDefault(false)
            return !playable
        }
        if (item.state == DownloadItemState.COMPLETED.name) {
            val playable = runCatching {
                OfflinePlayback.canPlayOffline(context, item)
            }.getOrDefault(false)
            return !playable
        }
        return false
    }

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
