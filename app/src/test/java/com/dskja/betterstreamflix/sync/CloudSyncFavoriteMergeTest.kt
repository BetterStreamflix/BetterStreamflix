package com.dskja.betterstreamflix.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncFavoriteMergeTest {

    @Test
    fun mergeForFirstLogin_unfavoriteWinsWhenNewer() {
        val remote = RemoteMediaState(
            userId = "u1",
            provider = "TMDb",
            mediaType = "movie",
            mediaId = "m1",
            title = "Film",
            isFavorite = true,
            favoritedAtMillis = 100L,
            clientUpdatedAtMillis = 100L,
        )
        val local = remote.copy(
            isFavorite = false,
            favoritedAtMillis = null,
            clientUpdatedAtMillis = 200L,
        )
        val merged = CloudSyncManager.mergeForFirstLogin(
            remote = listOf(remote),
            local = listOf(local),
            mergedAtMillis = 200L,
        ).single()
        assertFalse(merged.isFavorite)
        assertEquals(null, merged.favoritedAtMillis)
    }

    @Test
    fun mergeForFirstLogin_favoriteWinsWhenNewer() {
        val remote = RemoteMediaState(
            userId = "u1",
            provider = "TMDb",
            mediaType = "tv_show",
            mediaId = "s1",
            title = "Show",
            isFavorite = false,
            favoritedAtMillis = null,
            clientUpdatedAtMillis = 50L,
        )
        val local = remote.copy(
            isFavorite = true,
            favoritedAtMillis = 90L,
            clientUpdatedAtMillis = 90L,
        )
        val merged = CloudSyncManager.mergeForFirstLogin(
            remote = listOf(remote),
            local = listOf(local),
            mergedAtMillis = 90L,
        ).single()
        assertTrue(merged.isFavorite)
        assertEquals(90L, merged.favoritedAtMillis)
    }

    @Test
    fun pendingStateWins_usesFavoritedTimestamp() {
        val pending = RemoteMediaState(
            userId = "u1",
            provider = "TMDb",
            mediaType = "movie",
            mediaId = "m1",
            isFavorite = false,
            favoritedAtMillis = null,
            clientUpdatedAtMillis = 10L,
            watchedAtMillis = 300L,
        )
        val remote = pending.copy(
            isFavorite = true,
            favoritedAtMillis = 200L,
            clientUpdatedAtMillis = 999L,
            watchedAtMillis = 100L,
        )
        assertTrue(CloudSyncManager.pendingStateWins(pending, remote))
    }
}
