package com.betterstreamflix.app.download

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.betterstreamflix.app.R
import com.betterstreamflix.app.profiles.ProfileManager
import com.betterstreamflix.app.utils.UserPreferences
import java.io.File

object DownloadStorage {
    private const val DIR_NAME = "downloads"
    private const val PROFILES_ROOT = "profiles"
    private const val PUBLIC_FOLDER = "BetterStreamflix"
    private const val MIN_FREE_BYTES = 500L * 1024L * 1024L

    fun location(): DownloadStorageLocation = runCatching {
        UserPreferences.downloadStorageLocation
    }.getOrDefault(DownloadStorageLocation.INTERNAL)

    /**
     * Visible per-profile segment under the storage root, e.g.
     * `profiles/Kids__a1b2c3d4e5f6` or `profiles/default`.
     */
    fun profileFolderSegment(
        profileId: String = ProfileManager.activeProfileId,
        displayName: String? = ProfileManager.activeProfile()?.displayName,
    ): String {
        val safeName = DownloadSeriesFolders.sanitizeFolderName(
            displayName?.takeIf { it.isNotBlank() } ?: profileId,
        )
        return if (profileId == ProfileManager.DEFAULT_PROFILE_ID) {
            "$PROFILES_ROOT/$safeName"
        } else {
            "$PROFILES_ROOT/${safeName}__$profileId"
        }
    }

    fun profileDownloadsDir(
        context: Context,
        profileId: String,
        displayName: String? = null,
    ): File {
        val root = storageRootDir(context)
        return File(root, profileFolderSegment(profileId, displayName))
    }

    fun downloadsDir(context: Context): File {
        val preferred = File(
            storageRootDir(context),
            profileFolderSegment(),
        )
        ensureWritableDir(preferred)?.let { return it }
        if (preferred.isDirectory && preferred.canRead()) return preferred
        if (location() == DownloadStorageLocation.CUSTOM_FOLDER ||
            location() == DownloadStorageLocation.REMOVABLE
        ) {
            // Never retarget an open SimpleCache onto a different volume.
            return preferred
        }
        val fallbackRoot = appExternalDownloads(context.applicationContext)
        val nested = File(fallbackRoot, profileFolderSegment())
        return ensureWritableDir(nested) ?: nested.also { if (!it.exists()) it.mkdirs() }
    }

    /** Shared volume root (not profile-nested). */
    fun storageRootDir(context: Context): File {
        val app = context.applicationContext
        val preferred = when (location()) {
            DownloadStorageLocation.INTERNAL ->
                File(app.filesDir, DIR_NAME)
            DownloadStorageLocation.APP_EXTERNAL ->
                appExternalDownloads(app)
            DownloadStorageLocation.PUBLIC_MOVIES ->
                publicMoviesDownloads()
            DownloadStorageLocation.REMOVABLE ->
                removableAppDownloadsDir(app) ?: File(app.filesDir, "downloads-volume-missing")
            DownloadStorageLocation.CUSTOM_FOLDER ->
                DownloadTreeAccess.media3CacheDir(app)
        }
        ensureWritableDir(preferred)?.let { return it }
        if (preferred.isDirectory && preferred.canRead()) return preferred
        if (location() == DownloadStorageLocation.CUSTOM_FOLDER ||
            location() == DownloadStorageLocation.REMOVABLE
        ) {
            return preferred
        }
        return ensureWritableDir(appExternalDownloads(app)) ?: File(app.filesDir, DIR_NAME).also {
            if (!it.exists()) it.mkdirs()
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
                location() == DownloadStorageLocation.REMOVABLE &&
                    !hasRemovableStorage(context) ->
                    R.string.settings_download_storage_removable_unavailable
                location() == DownloadStorageLocation.CUSTOM_FOLDER -> {
                    val health = DownloadTreeAccess.customStorageHealth(context)
                    when {
                        // Volume cache still writable: grant loss is a warning, not a block.
                        health.volumeOk -> null
                        health.hasTree && !health.grantOk ->
                            R.string.settings_download_storage_permission_lost
                        else -> R.string.settings_download_storage_unavailable
                    }
                }
                !isStorageAvailable(context) ->
                    R.string.settings_download_storage_unavailable
                else -> null
            }
        }.getOrNull()
    }

    /** True when PUBLIC_MOVIES is selected but writes fall back to app-external storage. */
    fun isPublicMoviesFallback(context: Context): Boolean {
        if (location() != DownloadStorageLocation.PUBLIC_MOVIES) return false
        val actual = downloadsDir(context).absolutePath
        val preferred = publicMoviesDownloads().absolutePath
        return !actual.startsWith(preferred)
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
        val profileLabel = context.getString(
            R.string.downloads_profile_folder_label,
            profileFolderSegment(),
        )
        val body = when (location()) {
            DownloadStorageLocation.CUSTOM_FOLDER -> {
                val health = DownloadTreeAccess.customStorageHealth(context)
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
                    if (health.volumeOk && !health.grantOk && health.hasTree) {
                        append(context.getString(R.string.settings_download_storage_permission_lost))
                        append('\n')
                    }
                    append(context.getString(R.string.settings_download_storage_cache_path, cachePath))
                }
            }
            DownloadStorageLocation.PUBLIC_MOVIES -> {
                if (isPublicMoviesFallback(context)) {
                    context.getString(R.string.settings_download_storage_public_fallback, cachePath)
                } else {
                    cachePath
                }
            }
            DownloadStorageLocation.REMOVABLE ->
                context.getString(R.string.settings_download_storage_cache_path, cachePath)
            else -> cachePath
        }
        return "$profileLabel\n$body"
    }

    fun usedBytes(context: Context): Long {
        val downloads = downloadsDir(context)
        val cache = cacheDir(context)
        val downloadsBytes = dirSizeBytes(downloads)
        // cacheDir is nested under downloadsDir today; only sum separately if paths diverge.
        val cacheNested = cache.absolutePath.startsWith(downloads.absolutePath)
        val cacheBytes = if (cacheNested) 0L else dirSizeBytes(cache)
        return downloadsBytes + cacheBytes
    }

    private fun dirSizeBytes(dir: java.io.File): Long =
        runCatching {
            if (!dir.exists()) 0L
            else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)

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

    fun formatBytes(bytes: Long): String = formatBytes(context = null, bytes = bytes)

    fun formatBytes(context: Context?, bytes: Long): String {
        if (bytes < 0) {
            return context?.getString(R.string.download_size_unknown) ?: "—"
        }
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> {
                val value = bytes / gb
                context?.getString(R.string.download_size_gb, value)
                    ?: String.format("%.1f GB", value)
            }
            bytes >= mb -> {
                val value = bytes / mb
                context?.getString(R.string.download_size_mb, value)
                    ?: String.format("%.0f MB", value)
            }
            bytes >= kb -> {
                val value = bytes / kb
                context?.getString(R.string.download_size_kb, value)
                    ?: String.format("%.0f KB", value)
            }
            else -> context?.getString(R.string.download_size_b, bytes) ?: "$bytes B"
        }
    }

    fun deleteQuietly(file: File?) {
        runCatching {
            if (file == null) return
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
    }
}
