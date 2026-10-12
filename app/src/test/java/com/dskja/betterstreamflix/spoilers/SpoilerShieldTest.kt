package com.dskja.betterstreamflix.spoilers

import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.TvShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpoilerShieldTest {

    @Before
    fun clear() {
        SpoilerShield.clearSession()
    }

    private fun ep(
        id: String = "e1",
        title: String? = "The Twist",
        watched: Boolean = false,
        showId: String = "show1",
    ) = Episode(
        id = id,
        number = 3,
        title = title,
        poster = "https://example.test/p.jpg",
        tvShow = TvShow(id = showId, title = "Show"),
    ).also { it.isWatched = watched }

    @Test
    fun inactive_neverShields() {
        assertFalse(
            SpoilerShield.isShielded(
                ep(),
                isKids = { false },
                adultOptIn = { false },
            ),
        )
    }

    @Test
    fun kids_shieldsUnwatched() {
        assertTrue(
            SpoilerShield.isShielded(
                ep(),
                isKids = { true },
                adultOptIn = { false },
            ),
        )
    }

    @Test
    fun adultOptIn_shieldsUnwatched() {
        assertTrue(
            SpoilerShield.isShielded(
                ep(),
                isKids = { false },
                adultOptIn = { true },
            ),
        )
    }

    @Test
    fun inProgressWatchHistory_notShielded() {
        val episode = ep().also {
            it.watchHistory = com.dskja.betterstreamflix.models.WatchItem.WatchHistory(
                lastPlaybackPositionMillis = 60_000L,
                durationMillis = 120_000L,
                lastEngagementTimeUtcMillis = 1L,
            )
        }
        assertFalse(
            SpoilerShield.isShielded(
                episode,
                isKids = { true },
                adultOptIn = { false },
            ),
        )
    }

    @Test
    fun watched_notShielded() {
        assertFalse(
            SpoilerShield.isShielded(
                ep(watched = true),
                isKids = { true },
                adultOptIn = { true },
            ),
        )
    }

    @Test
    fun unlockEpisode_clearsShield() {
        val episode = ep(id = "ep-9")
        SpoilerShield.unlockEpisode("ep-9")
        assertFalse(
            SpoilerShield.isShielded(
                episode,
                isKids = { true },
                adultOptIn = { false },
            ),
        )
    }

    @Test
    fun unlockShow_clearsAllEpisodes() {
        SpoilerShield.unlockShow("show1")
        assertFalse(
            SpoilerShield.isShielded(
                ep(id = "a"),
                isKids = { true },
                adultOptIn = { false },
            ),
        )
        assertFalse(
            SpoilerShield.isShielded(
                ep(id = "b"),
                isKids = { true },
                adultOptIn = { false },
            ),
        )
    }

    @Test
    fun displayTitle_hidesSpoilers() {
        val shielded = SpoilerShield.displayTitle(
            ep(title = "Secret Ending"),
            episodeNumberFallback = { "Episode $it" },
            isKids = { true },
            adultOptIn = { false },
        )
        assertEquals("Episode 3", shielded)
        SpoilerShield.unlockEpisode("e1")
        val open = SpoilerShield.displayTitle(
            ep(title = "Secret Ending"),
            episodeNumberFallback = { "Episode $it" },
            isKids = { true },
            adultOptIn = { false },
        )
        assertEquals("Secret Ending", open)
    }

    @Test
    fun displayOverview_placeholderWhenShielded() {
        val text = SpoilerShield.displayOverview(
            ep().also { it.overview = "They reveal the villain." },
            shieldedPlaceholder = "Hidden",
            isKids = { true },
            adultOptIn = { false },
        )
        assertEquals("Hidden", text)
    }
}
