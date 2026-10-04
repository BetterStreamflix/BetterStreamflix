package com.dskja.betterstreamflix.download

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.UserPreferences
import java.io.File

object DownloadStorage {
    private const val DIR_NAME = "downloads"
    private const val PUBLIC_FOLDER = "BetterStreamflix"
    private const val MIN_FREE_BYTES = 500L * 1024L * 1024L

    fun location(): DownloadStorageLocation = runCatching {
        UserPreferences.downloadStorageLocation
    }.getOrDefault(DownloadStorageLocation.INTERNAL)

    fun downloadsDir(context: Context): File {
        val app = context.applicationContext
        val preferred = when (location()) {
            DownloadStorageLocation.INTERNAL ->
                File(app.filesDir, DIR_NAME)
            DownloadStorageLocation.APP_EXTERNAL ->
                appExternalDownloads(app)
            DownloadStorageLocation.PUBLIC_MOVIES ->
                publicMoviesDownloads()
            DownloadStorageLocation.REMOVABLE ->
                removableAppDownloadsDir(app) ?: appExternalDownloads(app)
            DownloadStorageLocation.CUSTOM_FOLDER -> {
                if (!DownloadTreeAccess.validateCustomStorage(app)) {
                    // Permission lost or SD ejected — fall back without pretending.
                    appExternalDownloads(app)
                } else {
                    DownloadTreeAccess.media3CacheDir(app)
                }
            }
        }
        return ensureWritableDir(preferred) ?: appExternalDownloads(app).also { fallback ->
            if (!fallback.exists()) fallback.mkdirs()
        }
    }

    /**
     * Whether the configured storage is currently usable for new downloads / offline play.
     */
    fun isStorageAvailable(context: Context): Boolean {
        return when (location()) {
            DownloadStorageLocation.CUSTOM_FOLDER ->
                DownloadTreeAccess.validateCustomStorage(context) &&
                    ensureWritableDir(downloadsDir(context)) != null
            DownloadStorageLocation.REMOVABLE ->
                removableAppDownloadsDir(context) != null
            else -> ensureWritableDir(downloadsDir(context)) != null
        }
    }

    fun storageUnavailableReason(context: Context): Int? {
        return runCatching {
            when {
                location() == DownloadStorageLocation.CUSTOM_FOLDER &&
                    DownloadTreeAccess.hasTree() &&
                    !DownloadTreeAccess.hasPersistedPermission(context) ->
                    R.string.settings_download_storage_permission_lost
                location() == DownloadStorageLocation.REMOVABLE &&
                    !hasRemovableStorage(context) ->
                    R.string.settings_download_storage_removable_unavailable
                location() == DownloadStorageLocation.CUSTOM_FOLDER &&
                    !DownloadTreeAccess.validateCustomStorage(context) ->
                    R.string.settings_download_storage_unavailable
                !isStorageAvailable(context) ->
                    R.string.settings_download_storage_unavailable
                else -> null
            }
        }.getOrNull()
    }

    private fun appExternalDownloads(app: Context): File {
        val root = app.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: app.getExternalFilesDir(null)
            ?: app.filesDir
        return File(root, DIR_NAME)
    }

    private fun publicMoviesDownloads(): File {
        val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        return File(File(movies, PUBLIC_FOLDER), DIR_NAME)
    }

    /**
     * App-specific downloads folder on a removable volume (index 1+ of getExternalFilesDirs).
     * When [volumeIdHint] is set (from a SAF tree), prefer a matching path.
     */
    fun removableAppDownloadsDir(context: Context, volumeIdHint: String? = null): File? {
        val dirs = context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES)
            ?.filterNotNull()
            ?.filter { it.exists() || it.mkdirs() }
            .orEmpty()
        if (dirs.size <= 1) {
            // Only primary — try getExternalFilesDirs(null) in case Movies is missing on SD.
            val all = context.getExternalFilesDirs(null)
                ?.filterNotNull()
                ?.filter { it.exists() || it.mkdirs() }
                .orEmpty()
            if (all.size <= 1) return null
            val match = matchVolume(all, volumeIdHint) ?: all.getOrNull(1) ?: return null
            val dir = File(match, DIR_NAME)
            return ensureWritableDir(dir)
        }
        val match = matchVolume(dirs, volumeIdHint) ?: dirs.getOrNull(1) ?: return null
        val dir = File(match, DIR_NAME)
        return ensureWritableDir(dir)
    }

    fun hasRemovableStorage(context: Context): Boolean =
        removableAppDownloadsDir(context) != null

    private fun matchVolume(dirs: List<File>, volumeIdHint: String?): File? {
        if (volumeIdHint.isNullOrBlank()) return null
        return dirs.firstOrNull { dir ->
            dir.absolutePath.contains(volumeIdHint, ignoreCase = true)
        }
    }

    /** Create [dir] if needed; return it only when it exists and is writable. */
    private fun ensureWritableDir(dir: File): File? {
        return try {
            if (!dir.exists() && !dir.mkdirs()) return null
            if (!dir.isDirectory || !dir.canWrite()) return null
            // Probe write — canWrite() alone is unreliable on some OEM public paths.
            val probe = File(dir, ".bsf_write_probe")
            val ok = runCatching {
                probe.writeText("ok")
                probe.delete()
                true
            }.getOrDefault(false)
            if (ok) dir else null
        } catch (_: Exception) {
            null
        }
    }

    fun cacheDir(context: Context): File {
        val dir = File(downloadsDir(context), "cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun subsDir(context: Context, contentKey: String): File {
        val safe = contentKey.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val dir = File(downloadsDir(context), "subs/$safe")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun absolutePathSummary(context: Context): String {
        val cachePath = downloadsDir(context).absolutePath
        return when (location()) {
            DownloadStorageLocation.CUSTOM_FOLDER -> {
                val treeName = DownloadTreeAccess.displayName(context)
                val volume = DownloadTreeAccess.volumeLabel(
                    context,
                    DownloadTreeAccess.volumeIdFromTree(DownloadTreeAccess.treeUri()),
                )
                buildString {
                    if (!treeName.isNullOrBlank()) {
                        append(context.getString(R.string.settings_download_storage_saf_label, treeName))
                        append('\n')
                    }
                    if (!volume.isNullOrBlank()) {
                        append(context.getString(R.string.settings_download_storage_volume_label, volume))
                        append('\n')
                    }
                    append(context.getString(R.string.settings_download_storage_cache_path, cachePath))
                }
            }
            DownloadStorageLocation.REMOVABLE ->
                context.getString(R.string.settings_download_storage_cache_path, cachePath)
            else -> cachePath
        }
    }

    fun usedBytes(context: Context): Long =
        downloadsDir(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun freeBytes(context: Context): Long {
        return try {
            val path = downloadsDir(context).absolutePath
            val stat = StatFs(path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) {
            Environment.getDataDirectory().usableSpace
        }
    }

    fun hasEnoughSpace(context: Context, estimatedBytes: Long = 0L): Boolean {
        val needed = maxOf(MIN_FREE_BYTES, estimatedBytes + (100L * 1024L * 1024L))
        return freeBytes(context) >= needed
    }

    fun isOverSoftLimit(context: Context): Boolean {
        val softLimit = UserPreferences.downloadSoftLimitGb.toLong() * 1024L * 1024L * 1024L
        return softLimit > 0 && usedBytes(context) >= softLimit
    }

    fun isLowSpace(context: Context): Boolean =
        freeBytes(context) < MIN_FREE_BYTES || isOverSoftLimit(context)

    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "—"
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> String.format("%.1f GB", bytes / gb)
            bytes >= mb -> String.format("%.0f MB", bytes / mb)
            bytes >= kb -> String.format("%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    fun deleteQuietly(file: File?) {
        runCatching {
            if (file == null) return
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
    }
}
