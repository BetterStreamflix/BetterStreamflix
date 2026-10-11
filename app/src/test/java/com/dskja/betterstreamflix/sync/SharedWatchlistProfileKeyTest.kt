package com.dskja.betterstreamflix.sync

import com.dskja.betterstreamflix.profiles.ProfileManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedWatchlistProfileKeyTest {

    @Test
    fun listTitleForProfile_encodesProfileId() {
        assertEquals(
            "Watchlist [p:kids01]",
            SharedWatchlistRepository.listTitleForProfile("kids01"),
        )
    }

    @Test
    fun matchesProfileList_prefersTitleMarker() {
        val kids = RemoteSharedWatchlist(
            title = "Watchlist [p:kids01]",
            shareToken = "abcdef12abcdef12abcdef12abcdef12",
        )
        val adult = RemoteSharedWatchlist(
            title = "Watchlist [p:${ProfileManager.DEFAULT_PROFILE_ID}]",
            shareToken = "abcdef12abcdef12abcdef12abcdef13",
        )
        assertTrue(SharedWatchlistRepository.matchesProfileList(kids, "kids01"))
        assertFalse(SharedWatchlistRepository.matchesProfileList(kids, ProfileManager.DEFAULT_PROFILE_ID))
        assertTrue(
            SharedWatchlistRepository.matchesProfileList(adult, ProfileManager.DEFAULT_PROFILE_ID),
        )
    }

    @Test
    fun matchesProfileList_legacyTitleBelongsToDefaultOnly() {
        val legacy = RemoteSharedWatchlist(
            title = "Watchlist",
            shareToken = "abcdef12abcdef12abcdef12abcdef14",
        )
        assertTrue(
            SharedWatchlistRepository.matchesProfileList(
                legacy,
                ProfileManager.DEFAULT_PROFILE_ID,
            ),
        )
        assertFalse(SharedWatchlistRepository.matchesProfileList(legacy, "kids01"))
    }
}
