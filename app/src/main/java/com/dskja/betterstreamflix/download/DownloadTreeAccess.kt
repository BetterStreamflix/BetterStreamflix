package com.dskja.betterstreamflix.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import com.dskja.betterstreamflix.utils.UserPreferences
import java.io.File

/**
 * Persisted Storage Access Framework tree for download folder selection (SD card etc.).
 *
 * Media3 [androidx.media3.datasource.cache.SimpleCache] requires a real [File]. When the
 * user picks a SAF folder on a removable volume we bind Media3 to that volume's
 * **app-specific** downloads directory (writable under scoped storage) and keep the
 * tree URI for display + permission continuity + progressive export markers.
 */
object DownloadTreeAccess {
    private const val TAG = "DownloadTreeAccess"
    private const val APP_FOLDER = "BetterStreamflix"
    private const val DOWNLOADS_FOLDER = "downloads"
    private const val README_NAME = "BetterStreamflix-offline.txt"

    fun treeUri(): Uri? =
        UserPreferences.downloadTreeUri.trim().takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }

    fun hasTree(): Boolean = treeUri() != null

    /** True when the persisted tree grant is still held by the OS. */
    fun hasPersistedPermission(context: Context): Boolean {
        val uri = treeUri() ?: return false
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    }

    fun persistTree(context: Context, uri: Uri): Boolean {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            UserPreferences.downloadTreeUri = uri.toString()
            UserPreferences.downloadStorageLocation = DownloadStorageLocation.CUSTOM_FOLDER
            // Best-effort marker in the user-visible folder explaining where offline
            // Media3 cache actually lives (app-specific dir on the same volume).
            writeLocationReadme(context)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Failed to persist tree permission: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set tree URI: ${e.message}")
            false
        }
    }

    fun clearTree(context: Context) {
        val uri = treeUri()
        if (uri != null) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        UserPreferences.downloadTreeUri = ""
        if (UserPreferences.downloadStorageLocation == DownloadStorageLocation.CUSTOM_FOLDER) {
            UserPreferences.downloadStorageLocation = DownloadStorageLocation.INTERNAL
        }
    }

    fun displayName(context: Context): String? {
        val uri = treeUri() ?: return null
        return runCatching {
            DocumentFile.fromTreeUri(context, uri)?.name
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfter(':')?.takeIf { it.isNotBlank() }
            ?: uri.toString()
    }

    fun downloadsDocument(context: Context): DocumentFile? {
        val uri = treeUri() ?: return null
        if (!hasPersistedPermission(context)) return null
        val root = DocumentFile.fromTreeUri(context, uri) ?: return null
        if (!root.canWrite()) return null
        val appDir = root.findFile(APP_FOLDER)?.takeIf { it.isDirectory }
            ?: root.createDirectory(APP_FOLDER)
            ?: return null
        return appDir.findFile(DOWNLOADS_FOLDER)?.takeIf { it.isDirectory }
            ?: appDir.createDirectory(DOWNLOADS_FOLDER)
    }

    /**
     * Media3 cache directory for CUSTOM_FOLDER: prefer the removable app-specific
     * folder matching the tree volume UUID.
     */
    fun media3CacheDir(context: Context): File {
        val app = context.applicationContext
        val volumeHint = volumeIdFromTree(treeUri())
        val secondary = DownloadStorage.removableAppDownloadsDir(app, volumeHint)
        if (secondary != null) return secondary
        // Tree on primary volume — still use app-external Movies/downloads.
        val primary = app.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: app.getExternalFilesDir(null)
            ?: app.filesDir
        val dir = File(primary, DOWNLOADS_FOLDER)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun volumeIdFromTree(uri: Uri?): String? {
        if (uri == null) return null
        val docId = uri.lastPathSegment ?: return null
        val decoded = Uri.decode(docId)
        val volume = decoded.substringBefore(':', missingDelimiterValue = "")
        return volume.takeIf { it.isNotBlank() && !volume.equals("primary", ignoreCase = true) }
    }

    fun volumeLabel(context: Context, volumeId: String?): String? {
        if (volumeId.isNullOrBlank()) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return volumeId
        val sm = context.getSystemService(StorageManager::class.java) ?: return volumeId
        return sm.storageVolumes.firstOrNull { volume ->
            volume.uuid.equals(volumeId, ignoreCase = true) ||
                volume.getDescription(context)?.contains(volumeId, ignoreCase = true) == true
        }?.getDescription(context) ?: volumeId
    }

    /** Validate CUSTOM_FOLDER is usable (permission + writable Media3 dir). */
    fun validateCustomStorage(context: Context): Boolean {
        if (!hasTree()) return false
        if (!hasPersistedPermission(context)) return false
        val dir = media3CacheDir(context)
        return dir.exists() && dir.canWrite()
    }

    private fun writeLocationReadme(context: Context) {
        runCatching {
            val docRoot = DocumentFile.fromTreeUri(context, treeUri() ?: return) ?: return
            val existing = docRoot.findFile(README_NAME)
            val target = existing ?: docRoot.createFile("text/plain", README_NAME) ?: return
            val cachePath = media3CacheDir(context).absolutePath
            val body = buildString {
                appendLine("BetterStreamflix offline downloads")
                appendLine()
                appendLine("Adaptive (HLS/DASH) offline cache is stored in the app folder on this")
                appendLine("storage volume so playback works reliably:")
                appendLine(cachePath)
                appendLine()
                appendLine("This folder was selected so BetterStreamflix can keep write access")
                appendLine("to the volume across reboots.")
            }
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
            }
        }.onFailure {
            Log.w(TAG, "Could not write SAF readme: ${it.message}")
        }
    }

    /**
     * OpenDocumentTree contract that requests persistable read/write grants.
     * AndroidX's default [ActivityResultContracts.OpenDocumentTree] omits those flags.
     */
    class PersistableOpenDocumentTree : ActivityResultContracts.OpenDocumentTree() {
        override fun createIntent(context: Context, input: Uri?): Intent {
            return super.createIntent(context, input).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
            }
        }
    }
}
