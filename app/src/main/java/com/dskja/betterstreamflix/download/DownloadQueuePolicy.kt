package com.dskja.betterstreamflix.download

/**
 * Media3 [androidx.media3.exoplayer.offline.Download.stopReason] values and
 * the rules that keep a user pause from being resumed by the connectivity watcher.
 */
object DownloadQueuePolicy {
    /** User tapped pause / Pause all. */
    const val STOP_USER = 1

    /** Wi-Fi-only held the download. Cleared when unmetered network returns. */
    const val STOP_CONNECTIVITY = 2

    fun shouldPauseForConnectivity(state: DownloadItemState): Boolean =
        state == DownloadItemState.QUEUED ||
            state == DownloadItemState.PREPARING ||
            state == DownloadItemState.DOWNLOADING

    fun shouldResumeAfterConnectivity(stopReason: Int, errorCode: String): Boolean =
        stopReason == STOP_CONNECTIVITY ||
            errorCode == DownloadErrorCode.WIFI_REQUIRED.name

    /**
     * A late Media3 callback must not rewind a newer Room row
     * (resume → queued, then a stale STOPPED event flips it back to paused).
     */
    fun isStaleDownloadEvent(
        entityUpdatedAt: Long,
        downloadUpdateTimeMs: Long,
        entityState: DownloadItemState,
        incoming: DownloadItemState,
    ): Boolean {
        if (downloadUpdateTimeMs <= 0L || downloadUpdateTimeMs >= entityUpdatedAt) return false
        // Older Media3 snapshot must not rewind a newer Room row.
        return when (entityState) {
            DownloadItemState.COMPLETED -> incoming != DownloadItemState.COMPLETED
            DownloadItemState.FAILED -> incoming != DownloadItemState.FAILED
            DownloadItemState.PAUSED ->
                incoming == DownloadItemState.DOWNLOADING ||
                    incoming == DownloadItemState.QUEUED ||
                    incoming == DownloadItemState.PREPARING
            DownloadItemState.QUEUED -> incoming == DownloadItemState.PAUSED
            DownloadItemState.DOWNLOADING ->
                incoming == DownloadItemState.PAUSED || incoming == DownloadItemState.QUEUED
            else -> false
        }
    }
}
