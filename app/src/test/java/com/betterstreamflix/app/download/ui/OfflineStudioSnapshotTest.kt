package com.betterstreamflix.app.download.ui

import com.betterstreamflix.app.download.DownloadErrorCode
import com.betterstreamflix.app.download.DownloadItemEntity
import com.betterstreamflix.app.download.DownloadItemState
import com.betterstreamflix.app.download.DownloadKind
import com.betterstreamflix.app.download.DownloadLanePolicy
import com.betterstreamflix.app.download.DownloadSeasonPackEntity
import com.betterstreamflix.app.download.SmartDownloadsPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class OfflineStudioSnapshotTest {

    private val now = 1_700_000_000_000L

    @Test
    fun build_splitsLanesAndCountsHealthExpiryPacks() {
        // Content-key-only focus so sibling episodes stay Queue / Smart (series focus
        // would promote the whole show into Watching — covered by DownloadLanePolicyTest).
        val focus = DownloadLanePolicy.watchingFocusFrom(
            providerName = "P",
            contentKey = "episode|P|show|1|1|e1",
            seriesKey = null,
        )
        val items = listOf(
            episode(
                id = "watch",
                contentKey = "episode|P|show|1|1|e1",
                state = DownloadItemState.DOWNLOADING.name,
                smart = false,
                updatedAt = now,
            ),
            episode(
                id = "queue",
                contentKey = "episode|P|show|1|2|e2",
                state = DownloadItemState.QUEUED.name,
                smart = false,
                updatedAt = now,
            ),
            episode(
                id = "smart",
                contentKey = "episode|P|show|1|3|e3",
                state = DownloadItemState.QUEUED.name,
                smart = true,
                updatedAt = now,
            ),
            episode(
                id = "bad",
                contentKey = "episode|P|show|1|4|e4",
                state = DownloadItemState.FAILED.name,
                errorCode = DownloadErrorCode.FILE_MISSING.name,
                updatedAt = now,
            ),
            episode(
                id = "stale",
                contentKey = "episode|P|show|1|5|e5",
                state = DownloadItemState.COMPLETED.name,
                title = "Aging Show",
                updatedAt = now - TimeUnit.DAYS.toMillis(40),
                completedAt = now - TimeUnit.DAYS.toMillis(40),
            ),
        )
        val packs = listOf(
            DownloadSeasonPackEntity(
                id = "pack1",
                providerName = "P",
                tvShowId = "show",
                tvShowTitle = "Show",
                seasonNumber = 1,
                totalEpisodes = 5,
            ),
        )
        val snap = OfflineStudioSnapshot.build(
            items = items,
            packs = packs,
            smartEnabled = true,
            smartLookahead = 99,
            smartSeriesQuota = 0,
            autoDeleteWatched = true,
            wifiOnly = true,
            softLimitGb = 20,
            maxConcurrent = 2,
            nowMs = now,
            watchingFocus = focus,
        )
        assertEquals(3, snap.activeCount)
        assertEquals(1, snap.watchingLaneCount)
        assertEquals(1, snap.queueLaneCount)
        assertEquals(1, snap.smartLaneCount)
        assertEquals(1, snap.completedCount)
        assertEquals(1, snap.packCount)
        assertTrue(snap.health.hasUnhealthy)
        assertEquals(1, snap.health.count)
        assertEquals(1, snap.expiryCount)
        assertTrue(snap.expiryTitles.any { it.contains("Aging") })
        assertTrue(snap.hasCareWork)
        assertEquals(SmartDownloadsPolicy.MAX_LOOKAHEAD, snap.smartLookahead)
        assertEquals(SmartDownloadsPolicy.MIN_SERIES_QUOTA, snap.smartSeriesQuota)
        assertTrue(snap.smartEnabled)
        assertTrue(snap.autoDeleteWatched)
        assertTrue(snap.wifiOnly)
        assertTrue(snap.seriesQuotaOccupied >= 1)
        assertEquals(1, snap.seriesWithQuota)
        assertTrue(snap.maxSeriesOccupancy >= 1)
        assertTrue(snap.maxSeriesOccupancy <= snap.seriesQuotaOccupied)
    }

    @Test
    fun build_maxSeriesOccupancy_isHottestSeriesNotSum() {
        val items = listOf(
            episode("h1", "episode|P|hot|1|1|e1", DownloadItemState.QUEUED.name, smart = true),
            episode("h2", "episode|P|hot|1|2|e2", DownloadItemState.QUEUED.name, smart = true),
            episode("h3", "episode|P|hot|1|3|e3", DownloadItemState.COMPLETED.name, smart = true),
            episode("c1", "episode|P|cool|1|1|c1", DownloadItemState.QUEUED.name, smart = true),
        )
        val snap = OfflineStudioSnapshot.build(
            items = items,
            packs = emptyList(),
            smartEnabled = true,
            smartLookahead = 3,
            smartSeriesQuota = 5,
            autoDeleteWatched = false,
            wifiOnly = false,
            softLimitGb = 0,
            maxConcurrent = 2,
            nowMs = now,
            watchingFocus = null,
        )
        // hot: 3 quota rows, cool: 1 → max is 3, sum is 4
        assertEquals(3, snap.maxSeriesOccupancy)
        assertEquals(4, snap.seriesQuotaOccupied)
        assertEquals(2, snap.seriesWithQuota)
    }

    @Test
    fun build_emptyLibrary_hasNoCareWork() {
        val snap = OfflineStudioSnapshot.build(
            items = emptyList(),
            packs = emptyList(),
            smartEnabled = false,
            smartLookahead = 3,
            smartSeriesQuota = 3,
            autoDeleteWatched = false,
            wifiOnly = false,
            softLimitGb = 0,
            maxConcurrent = 1,
            nowMs = now,
            watchingFocus = null,
        )
        assertEquals(0, snap.activeCount)
        assertFalse(snap.hasCareWork)
        assertEquals(0, snap.packCount)
        assertFalse(snap.health.hasUnhealthy)
    }

    private fun episode(
        id: String,
        contentKey: String,
        state: String,
        smart: Boolean = false,
        errorCode: String = "",
        title: String = "Show",
        updatedAt: Long = now,
        completedAt: Long = 0L,
    ): DownloadItemEntity {
        val showId = contentKey.split('|').getOrNull(2) ?: "show"
        return DownloadItemEntity(
            id = id,
            contentKey = contentKey,
            media3Id = id,
            providerName = "P",
            kind = DownloadKind.EPISODE.name,
            title = title,
            state = state,
            errorCode = errorCode,
            smartEnqueued = smart,
            updatedAt = updatedAt,
            completedAt = completedAt,
            createdAt = updatedAt,
            videoTypeJson = """{"showId":"$showId","showTitle":"$title","seasonNumber":1,"number":1}""",
        )
    }
}
