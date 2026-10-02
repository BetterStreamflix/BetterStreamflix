package com.dskja.betterstreamflix.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.dskja.betterstreamflix.utils.UserPreferences
import java.io.File

/**
 * Persisted Storage Access Framework tree for download folder selection (SD card etc.).
 * Media3 [SimpleCache] still needs a [File]; when a tree is set we prefer the matching
 * removable app-specific directory and expose the tree display name in Settings.
 */
object DownloadTreeAccess {
    private const val TAG = "DownloadTreeAccess"
    private const val APP_FOLDER = "BetterStreamflix"
    private const val DOWNLOADS_FOLDER = "downloads"

    fun treeUri(): Uri? =
        UserPreferences.downloadTreeUri.trim().takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }

    fun hasTree(): Boolean = treeUri() != null

    fun persistTree(context: Context, uri: Uri): Boolean {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            UserPreferences.downloadTreeUri = uri.toString()
            UserPreferences.downloadStorageLocation = DownloadStorageLocation.CUSTOM_FOLDER
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

    /** DocumentFile for BetterStreamflix/downloads under the persisted tree, if writable. */
    fun downloadsDocument(context: Context): DocumentFile? {
        val uri = treeUri() ?: return null
        val root = DocumentFile.fromTreeUri(context, uri) ?: return null
        if (!root.canWrite()) return null
        val appDir = root.findFile(APP_FOLDER)?.takeIf { it.isDirectory }
            ?: root.createDirectory(APP_FOLDER)
            ?: return null
        return appDir.findFile(DOWNLOADS_FOLDER)?.takeIf { it.isDirectory }
            ?: appDir.createDirectory(DOWNLOADS_FOLDER)
    }

    /**
     * Best [File] directory for Media3 when CUSTOM_FOLDER is selected:
     * match removable volume from the tree UUID, else secondary external, else app-external.
     */
    fun media3CacheDir(context: Context): File {
        val app = context.applicationContext
        val volumeHint = volumeIdFromTree(treeUri())
        val secondary = DownloadStorage.removableAppDownloadsDir(app, volumeHint)
        if (secondary != null) return secondary
        val primary = app.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: app.getExternalFilesDir(null)
            ?: app.filesDir
        val dir = File(primary, DOWNLOADS_FOLDER)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun volumeIdFromTree(uri: Uri?): String? {
        if (uri == null) return null
        // content://com.android.externalstorage.documents/tree/XXXX-XXXX%3AMovies
        val docId = uri.lastPathSegment ?: return null
        val decoded = Uri.decode(docId)
        val volume = decoded.substringBefore(':', missingDelimiterValue = "")
        return volume.takeIf { it.isNotBlank() && it != "primary" }
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
}
