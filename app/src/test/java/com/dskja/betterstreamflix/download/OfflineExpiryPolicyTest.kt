package com.dskja.betterstreamflix.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class OfflineExpiryPolicyTest {

    @Test
    fun untouchedThresholdIsThirtyDays() {
        assertEquals(TimeUnit.DAYS.toMillis(30), OfflineExpiryPolicy.UNTOUCHED_MS)
    }

    @Test
    fun cooldownIsFourteenDays() {
        assertEquals(TimeUnit.DAYS.toMillis(14), OfflineExpiryPolicy.COOLDOWN_MS)
    }

    @Test
    fun untouchedAtUsesMaxOfTimestamps() {
        val item = sample(
            createdAt = 10L,
            updatedAt = 50L,
            completedAt = 40L,
        )
        assertEquals(50L, OfflineExpiryPolicy.untouchedAt(item))
    }

    @Test
    fun isStaleOnlyForCompletedPastThreshold() {
        val now = System.currentTimeMillis()
        val threshold = OfflineExpiryPolicy.UNTOUCHED_MS
        val old = now - threshold - 1
        val stale = sample(
            state = DownloadItemState.COMPLETED.name,
            createdAt = old,
            updatedAt = old,
            completedAt = old,
        )
        val fresh = sample(
            state = DownloadItemState.COMPLETED.name,
            createdAt = now - 1_000L,
            updatedAt = now - 1_000L,
            completedAt = now - 1_000L,
        )
        val downloading = sample(
            state = DownloadItemState.DOWNLOADING.name,
            createdAt = old,
            updatedAt = old,
            completedAt = old,
        )
        assertTrue(OfflineExpiryPolicy.isStale(stale, nowMs = now))
        assertFalse(OfflineExpiryPolicy.isStale(fresh, nowMs = now))
        assertFalse(OfflineExpiryPolicy.isStale(downloading, nowMs = now))
    }

    @Test
    fun selectStaleOldestFirstAndCapsBatch() {
        val now = System.currentTimeMillis()
        val threshold = OfflineExpiryPolicy.UNTOUCHED_MS
        val items = (1..8).map { i ->
            val touch = now - threshold - i * 1_000L
            sample(
                id = "id$i",
                title = "Title $i",
                createdAt = touch,
                updatedAt = touch,
                completedAt = touch,
            )
        }
        val picked = OfflineExpiryPolicy.selectStale(items, nowMs = now, max = 5)
        assertEquals(5, picked.size)
        assertEquals(listOf("id8", "id7", "id6", "id5", "id4"), picked.map { it.id })
    }

    @Test
    fun formatTitleLinesCapsAndCountsExtra() {
        val items = (1..5).map { sample(id = "i$it", title = "T$it") }
        val (shown, extra) = OfflineExpiryPolicy.formatTitleLines(items, maxTitles = 3)
        assertEquals(listOf("T1", "T2", "T3"), shown)
        assertEquals(2, extra)
    }

    private fun sample(
        id: String = "id",
        title: String = "Title",
        state: String = DownloadItemState.COMPLETED.name,
        createdAt: Long = 1L,
        updatedAt: Long = 1L,
        completedAt: Long = 1L,
    ) = DownloadItemEntity(
        id = id,
        contentKey = "ck-$id",
        media3Id = "m3-$id",
        providerName = "P",
        kind = DownloadKind.EPISODE.name,
        title = title,
        state = state,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
    )
}
