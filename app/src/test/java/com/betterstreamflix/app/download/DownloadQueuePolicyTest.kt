package com.betterstreamflix.app.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueuePolicyTest {
    @Test
    fun connectivityPauseSkipsUserPausedRows() {
        assertTrue(DownloadQueuePolicy.shouldPauseForConnectivity(DownloadItemState.DOWNLOADING))
        assertTrue(DownloadQueuePolicy.shouldPauseForConnectivity(DownloadItemState.QUEUED))
        assertFalse(DownloadQueuePolicy.shouldPauseForConnectivity(DownloadItemState.PAUSED))
        assertFalse(DownloadQueuePolicy.shouldPauseForConnectivity(DownloadItemState.COMPLETED))
    }

    @Test
    fun connectivityResumeRequiresHoldSignal() {
        assertTrue(
            DownloadQueuePolicy.shouldResumeAfterConnectivity(
                DownloadQueuePolicy.STOP_CONNECTIVITY,
                errorCode = "",
            ),
        )
        assertTrue(
            DownloadQueuePolicy.shouldResumeAfterConnectivity(
                stopReason = 0,
                errorCode = DownloadErrorCode.WIFI_REQUIRED.name,
            ),
        )
        assertFalse(
            DownloadQueuePolicy.shouldResumeAfterConnectivity(
                DownloadQueuePolicy.STOP_USER,
                errorCode = "",
            ),
        )
    }

    @Test
    fun storagePauseAndResumeMirrorConnectivityRules() {
        assertTrue(DownloadQueuePolicy.shouldPauseForStorage(DownloadItemState.DOWNLOADING))
        assertFalse(DownloadQueuePolicy.shouldPauseForStorage(DownloadItemState.PAUSED))
        assertTrue(
            DownloadQueuePolicy.shouldResumeAfterStorage(
                DownloadQueuePolicy.STOP_STORAGE,
                errorCode = "",
            ),
        )
        assertTrue(
            DownloadQueuePolicy.shouldResumeAfterStorage(
                stopReason = 0,
                errorCode = DownloadErrorCode.LOW_STORAGE.name,
            ),
        )
        assertFalse(
            DownloadQueuePolicy.shouldResumeAfterStorage(
                DownloadQueuePolicy.STOP_USER,
                errorCode = "",
            ),
        )
        assertFalse(
            DownloadQueuePolicy.shouldResumeAfterStorage(
                DownloadQueuePolicy.STOP_CONNECTIVITY,
                errorCode = DownloadErrorCode.WIFI_REQUIRED.name,
            ),
        )
    }

    @Test
    fun staleStoppedEventDoesNotRewindQueuedResume() {
        assertTrue(
            DownloadQueuePolicy.isStaleDownloadEvent(
                entityUpdatedAt = 2_000L,
                downloadUpdateTimeMs = 1_000L,
                entityState = DownloadItemState.QUEUED,
                incoming = DownloadItemState.PAUSED,
            ),
        )
        assertFalse(
            DownloadQueuePolicy.isStaleDownloadEvent(
                entityUpdatedAt = 1_000L,
                downloadUpdateTimeMs = 2_000L,
                entityState = DownloadItemState.QUEUED,
                incoming = DownloadItemState.PAUSED,
            ),
        )
    }

    @Test
    fun staleDownloadingEventDoesNotRestartUserPause() {
        assertTrue(
            DownloadQueuePolicy.isStaleDownloadEvent(
                entityUpdatedAt = 5_000L,
                downloadUpdateTimeMs = 4_000L,
                entityState = DownloadItemState.PAUSED,
                incoming = DownloadItemState.DOWNLOADING,
            ),
        )
        assertFalse(
            DownloadQueuePolicy.isStaleDownloadEvent(
                entityUpdatedAt = 4_000L,
                downloadUpdateTimeMs = 5_000L,
                entityState = DownloadItemState.PAUSED,
                incoming = DownloadItemState.DOWNLOADING,
            ),
        )
    }
}
