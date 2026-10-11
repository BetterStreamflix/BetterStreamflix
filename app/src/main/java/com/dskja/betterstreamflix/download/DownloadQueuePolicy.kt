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

    /** Low free space / soft-limit hold. Cleared when storage recovers. */
    const val STOP_STORAGE = 3

    /**
     * Held so a higher Download Lane can use a parallel slot
     * (watching > queue > smart-auto). Cleared by [DownloadLaneScheduler].
     */
    const val STOP_LANE = 4

    fun shouldPauseForConnectivity(state: DownloadItemState): Boolean =
        state == DownloadItemState.QUEUED ||
            state == DownloadItemState.PREPARING ||
            state == DownloadItemState.DOWNLOADING

    fun shouldPauseForStorage(state: DownloadItemState): Boolean =
        shouldPauseForConnectivity(state)

    fun shouldResumeAfterConnectivity(stopReason: Int, errorCode: String): Boolean =
        stopReason == STOP_CONNECTIVITY ||
            errorCode == DownloadErrorCode.WIFI_REQUIRED.name

    fun shouldResumeAfterStorage(stopReason: Int, errorCode: String): Boolean =
        stopReason == STOP_STORAGE ||
            errorCode == DownloadErrorCode.LOW_STORAGE.name

    fun shouldResumeAfterLane(stopReason: Int, errorCode: String = ""): Boolean =
        stopReason == STOP_LANE ||
            errorCode == DownloadErrorCode.LANE_HOLD.name

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
