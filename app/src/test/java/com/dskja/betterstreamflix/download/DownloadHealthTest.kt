package com.dskja.betterstreamflix.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadHealthTest {

    @Test
    fun summarize_countsEpisodesAndDedupesContentKey() {
        val items = listOf(
            episode("e1", "episode|P|1|1|1|a", updatedAt = 1),
            episode("e1b", "episode|P|1|1|1|a", updatedAt = 2), // duplicate key
            episode("e2", "episode|P|1|1|2|b", updatedAt = 1),
            movie("m1", "movie|P|m1"),
            networkFail("n1", "episode|P|1|1|3|c"),
        )
        val summary = DownloadHealth.summarize(items)
        assertEquals(3, summary.count)
        assertEquals(2, summary.episodeCount)
        assertEquals(1, summary.movieCount)
        assertFalse(summary.episodesOnly)
        assertTrue(summary.hasUnhealthy)
        assertEquals(listOf("e1b", "e2", "m1"), summary.ids.sorted())
    }

    @Test
    fun summarize_episodesOnly() {
        val summary = DownloadHealth.summarize(
            listOf(
                episode("e1", "episode|P|1|1|1|a"),
                episode("e2", "episode|P|1|1|2|b"),
            ),
        )
        assertTrue(summary.episodesOnly)
        assertEquals(2, summary.count)
    }

    @Test
    fun isUnhealthy_falseForCompleted() {
        assertFalse(
            DownloadHealth.isUnhealthy(
                episode(
                    id = "ok",
                    contentKey = "episode|P|1|1|1|a",
                    state = DownloadItemState.COMPLETED.name,
                    errorCode = "",
                ),
            ),
        )
    }

    private fun episode(
        id: String,
        contentKey: String,
        state: String = DownloadItemState.FAILED.name,
        errorCode: String = DownloadErrorCode.STORAGE_RELOCATED.name,
        updatedAt: Long = 1L,
    ) = DownloadItemEntity(
        id = id,
        contentKey = contentKey,
        media3Id = id,
        providerName = "P",
        kind = DownloadKind.EPISODE.name,
        title = "Show",
        state = state,
        errorCode = errorCode,
        updatedAt = updatedAt,
    )

    private fun movie(id: String, contentKey: String) = DownloadItemEntity(
        id = id,
        contentKey = contentKey,
        media3Id = id,
        providerName = "P",
        kind = DownloadKind.MOVIE.name,
        title = "Film",
        state = DownloadItemState.FAILED.name,
        errorCode = DownloadErrorCode.FILE_MISSING.name,
    )

    @Test
    fun needsRedownload_noContext_ignoresCompletedUnplayable() {
        // Profile-agnostic path stays FAILED+FILE_MISSING/STORAGE_RELOCATED only (OS-019/OS-031).
        val completed = episode(
            id = "ok",
            contentKey = "episode|P|1|1|1|a",
            state = DownloadItemState.COMPLETED.name,
            errorCode = "",
        )
        assertFalse(DownloadSeriesFolders.needsRedownload(completed))
        assertFalse(DownloadHealth.isUnhealthy(completed))
    }

    private fun networkFail(id: String, contentKey: String) = DownloadItemEntity(
        id = id,
        contentKey = contentKey,
        media3Id = id,
        providerName = "P",
        kind = DownloadKind.EPISODE.name,
        title = "Show",
        state = DownloadItemState.FAILED.name,
        errorCode = DownloadErrorCode.NETWORK.name,
    )
}
