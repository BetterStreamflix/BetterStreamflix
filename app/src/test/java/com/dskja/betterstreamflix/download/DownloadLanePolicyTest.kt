package com.dskja.betterstreamflix.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadLanePolicyTest {

    private fun item(
        id: String,
        contentKey: String,
        smart: Boolean = false,
        state: DownloadItemState = DownloadItemState.QUEUED,
        errorCode: String = "",
        createdAt: Long = 0L,
        provider: String = "Demo",
        kind: DownloadKind = DownloadKind.EPISODE,
    ) = DownloadItemEntity(
        id = id,
        contentKey = contentKey,
        media3Id = id,
        providerName = provider,
        kind = kind.name,
        title = id,
        state = state.name,
        smartEnqueued = smart,
        errorCode = errorCode,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    @Test
    fun laneOf_watchingBeatsSmart() {
        val focus = DownloadLanePolicy.WatchingFocus(
            providerName = "Demo",
            contentKey = "episode|Demo|show|1|2|e2",
            seriesKey = "series|Demo|show",
        )
        val smartSameShow = item(
            id = "s",
            contentKey = "episode|Demo|show|1|3|e3",
            smart = true,
        )
        assertEquals(DownloadLane.WATCHING, DownloadLanePolicy.laneOf(smartSameShow, focus))
        assertEquals(
            DownloadLane.SMART_AUTO,
            DownloadLanePolicy.laneOf(smartSameShow.copy(contentKey = "episode|Demo|other|1|1|x"), focus),
        )
    }

    @Test
    fun laneOf_userQueueWhenNotSmart() {
        val row = item(id = "q", contentKey = "movie|Demo|m1", kind = DownloadKind.MOVIE)
        assertEquals(DownloadLane.QUEUE, DownloadLanePolicy.laneOf(row, focus = null))
    }

    @Test
    fun plan_prefersWatchingThenQueueThenSmart() {
        val focus = DownloadLanePolicy.WatchingFocus(
            providerName = "Demo",
            contentKey = "episode|Demo|show|1|1|e1",
            seriesKey = "series|Demo|show",
        )
        val watching = item("w", "episode|Demo|show|1|2|e2", createdAt = 30)
        val queue = item("q", "movie|Demo|m1", kind = DownloadKind.MOVIE, createdAt = 10)
        val smart = item("s", "episode|Demo|other|1|1|x", smart = true, createdAt = 5)
        val plan = DownloadLanePolicy.plan(
            items = listOf(smart, queue, watching),
            slots = 1,
            focus = focus,
        )
        assertEquals(setOf("w"), plan.runIds)
        assertEquals(setOf("q", "s"), plan.holdIds)
    }

    @Test
    fun plan_fillsSlotsAcrossLanes() {
        val queue1 = item("q1", "movie|Demo|a", kind = DownloadKind.MOVIE, createdAt = 1)
        val queue2 = item("q2", "movie|Demo|b", kind = DownloadKind.MOVIE, createdAt = 2)
        val smart = item("s", "episode|Demo|z|1|1|z", smart = true, createdAt = 3)
        val plan = DownloadLanePolicy.plan(listOf(smart, queue2, queue1), slots = 2, focus = null)
        assertEquals(setOf("q1", "q2"), plan.runIds)
        assertEquals(setOf("s"), plan.holdIds)
    }

    @Test
    fun plan_laneHeldIsCandidate_userPauseIsNot() {
        val held = item(
            id = "h",
            contentKey = "movie|Demo|h",
            kind = DownloadKind.MOVIE,
            state = DownloadItemState.PAUSED,
            errorCode = DownloadErrorCode.LANE_HOLD.name,
        )
        val userPaused = item(
            id = "u",
            contentKey = "movie|Demo|u",
            kind = DownloadKind.MOVIE,
            state = DownloadItemState.PAUSED,
            errorCode = "",
        )
        assertTrue(DownloadLanePolicy.isLaneCandidate(held))
        assertFalse(DownloadLanePolicy.isLaneCandidate(userPaused))
        val plan = DownloadLanePolicy.plan(listOf(held, userPaused), slots = 1, focus = null)
        assertEquals(setOf("h"), plan.runIds)
        assertTrue(plan.holdIds.isEmpty())
    }

    @Test
    fun stopLaneConstantDistinct() {
        assertTrue(DownloadQueuePolicy.STOP_LANE > DownloadQueuePolicy.STOP_STORAGE)
        assertTrue(DownloadQueuePolicy.shouldResumeAfterLane(DownloadQueuePolicy.STOP_LANE))
        assertFalse(DownloadQueuePolicy.shouldResumeAfterLane(DownloadQueuePolicy.STOP_USER))
    }

    @Test
    fun titleAndSizeSort_primaryKeyIsLaneRank() {
        val focus = DownloadLanePolicy.WatchingFocus(
            providerName = "Demo",
            contentKey = "episode|Demo|show|1|1|e1",
            seriesKey = "series|Demo|show",
        )
        val watching = item("w", "episode|Demo|show|1|2|e2", createdAt = 1).copy(title = "Zulu")
        val queue = item("q", "movie|Demo|m1", kind = DownloadKind.MOVIE, createdAt = 2)
            .copy(title = "Alpha", contentLength = 9_000L)
        val smart = item("s", "episode|Demo|other|1|1|x", smart = true, createdAt = 3)
            .copy(title = "Beta", contentLength = 99_000L)
        val titleSorted = listOf(smart, queue, watching).sortedWith(
            compareBy<DownloadItemEntity> { DownloadLanePolicy.laneOf(it, focus).rank }
                .thenBy { it.title.lowercase() },
        )
        assertEquals(listOf("w", "q", "s"), titleSorted.map { it.id })
        val sizeSorted = listOf(smart, queue, watching).sortedWith(
            compareBy<DownloadItemEntity> { DownloadLanePolicy.laneOf(it, focus).rank }
                .thenByDescending { maxOf(it.contentLength, it.bytesDownloaded) },
        )
        assertEquals(listOf("w", "q", "s"), sizeSorted.map { it.id })
        assertTrue(DownloadLane.WATCHING.rank < DownloadLane.QUEUE.rank)
        assertTrue(DownloadLane.QUEUE.rank < DownloadLane.SMART_AUTO.rank)
    }
}
