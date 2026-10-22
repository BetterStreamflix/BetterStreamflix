package com.betterstreamflix.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartDownloadsPolicyTest {
    @Test
    fun clampsLookaheadAndQuota() {
        assertEquals(1, SmartDownloadsPolicy.clampLookahead(0))
        assertEquals(5, SmartDownloadsPolicy.clampLookahead(99))
        assertEquals(3, SmartDownloadsPolicy.clampLookahead(3))
        assertEquals(1, SmartDownloadsPolicy.clampSeriesQuota(0))
        assertEquals(10, SmartDownloadsPolicy.clampSeriesQuota(99))
    }

    @Test
    fun completedUnwatchedCountsTowardQuota() {
        val item = sampleEpisode(state = DownloadItemState.COMPLETED, watched = false)
        assertTrue(SmartDownloadsPolicy.countsTowardSeriesQuota(item))
    }

    @Test
    fun watchedCompletedDoesNotCount() {
        val item = sampleEpisode(state = DownloadItemState.COMPLETED, watched = true)
        assertFalse(SmartDownloadsPolicy.countsTowardSeriesQuota(item))
    }

    @Test
    fun activeStatesCountTowardQuota() {
        assertTrue(
            SmartDownloadsPolicy.countsTowardSeriesQuota(
                sampleEpisode(state = DownloadItemState.QUEUED),
            ),
        )
        assertTrue(
            SmartDownloadsPolicy.countsTowardSeriesQuota(
                sampleEpisode(state = DownloadItemState.DOWNLOADING),
            ),
        )
        assertFalse(
            SmartDownloadsPolicy.countsTowardSeriesQuota(
                sampleEpisode(state = DownloadItemState.FAILED),
            ),
        )
    }

    @Test
    fun manualQueueDoesNotCountTowardSmartQuota() {
        assertFalse(
            SmartDownloadsPolicy.countsTowardSeriesQuota(
                sampleEpisode(state = DownloadItemState.QUEUED, smart = false),
            ),
        )
    }

    @Test
    fun enqueueBudgetHonorsQuotaAndLookahead() {
        assertEquals(
            3,
            SmartDownloadsPolicy.enqueueBudget(
                missingUpcoming = 5,
                occupancy = 0,
                lookahead = 3,
                quota = 3,
            ),
        )
        assertEquals(
            1,
            SmartDownloadsPolicy.enqueueBudget(
                missingUpcoming = 5,
                occupancy = 2,
                lookahead = 3,
                quota = 3,
            ),
        )
        assertEquals(
            0,
            SmartDownloadsPolicy.enqueueBudget(
                missingUpcoming = 5,
                occupancy = 3,
                lookahead = 3,
                quota = 3,
            ),
        )
        assertEquals(
            2,
            SmartDownloadsPolicy.enqueueBudget(
                missingUpcoming = 2,
                occupancy = 0,
                lookahead = 3,
                quota = 5,
            ),
        )
    }

    @Test
    fun seriesOccupancyGroupsByShowKey() {
        val a = sampleEpisode(showId = "show-a", state = DownloadItemState.COMPLETED, watched = false)
        val b = sampleEpisode(showId = "show-b", state = DownloadItemState.QUEUED)
        val keyA = SmartDownloadsPolicy.seriesKeyForShow("Provider", "show-a")
        assertEquals(1, SmartDownloadsPolicy.seriesOccupancy(listOf(a, b), keyA))
    }

    @Test
    fun seasonEnqueueBudgetIsFullMissing_notQuotaCapped() {
        assertEquals(
            12,
            SmartDownloadsPolicy.seasonEnqueueBudget(
                missingInSeason = 12,
                occupancy = 0,
                quota = 3,
            ),
        )
        assertEquals(
            12,
            SmartDownloadsPolicy.seasonEnqueueBudget(
                missingInSeason = 12,
                occupancy = 3,
                quota = 3,
            ),
        )
        assertEquals(
            5,
            SmartDownloadsPolicy.seasonEnqueueBudget(
                missingInSeason = 5,
                occupancy = 0,
                quota = 10,
            ),
        )
        assertEquals(
            0,
            SmartDownloadsPolicy.seasonEnqueueBudget(
                missingInSeason = 0,
                occupancy = 0,
                quota = 3,
            ),
        )
    }

    private fun sampleEpisode(
        showId: String = "show-a",
        state: DownloadItemState = DownloadItemState.QUEUED,
        watched: Boolean = false,
        smart: Boolean = true,
    ): DownloadItemEntity = DownloadItemEntity(
        id = "id-$showId-${state.name}",
        contentKey = "episode|Provider|$showId|1|1|ep1",
        media3Id = "m3",
        providerName = "Provider",
        kind = DownloadKind.EPISODE.name,
        title = "Show",
        state = state.name,
        watchedOffline = watched,
        smartEnqueued = smart,
        videoTypeJson = """{"id":"ep1","showId":"$showId","showTitle":"Show"}""",
    )
}
