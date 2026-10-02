package com.dskja.betterstreamflix.download

/**
 * Where offline Media3 downloads and sidecar files are stored.
 * Changing location does not migrate existing files — restart downloads after switching.
 */
enum class DownloadStorageLocation {
    /** App-private internal storage (default, most secure). */
    INTERNAL,

    /** App-specific external storage (Android/data/… — visible in Files on many devices). */
    APP_EXTERNAL,

    /** Shared Movies/BetterStreamflix folder (easier to find; may need legacy permission on old APIs). */
    PUBLIC_MOVIES,

    /**
     * Removable / SD card app-specific folder via [android.content.Context.getExternalFilesDirs].
     * Preferred path for Media3 [androidx.media3.datasource.cache.SimpleCache] on SD.
     */
    REMOVABLE,

    /**
     * User-picked folder via Storage Access Framework (tree URI).
     * Media3 cache uses the matching removable app dir when available; the tree URI is
     * persisted for display, progressive exports, and permission continuity.
     */
    CUSTOM_FOLDER;

    companion object {
        fun fromKey(raw: String?): DownloadStorageLocation {
            val key = raw?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.name == key } ?: INTERNAL
        }
    }
}
