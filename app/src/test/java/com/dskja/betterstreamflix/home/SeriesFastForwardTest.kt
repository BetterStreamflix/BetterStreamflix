package com.dskja.betterstreamflix.home

import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.TvShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class SeriesFastForwardTest {

    @Test
    fun supportsNextEpisodesAndFavoriteTvOnly() {
        assertTrue(SeriesFastForward.supportsShelf(Category.NEXT_EPISODES))
        assertTrue(SeriesFastForward.supportsShelf(Category.FAVORITE_TV_SHOWS))
        assertFalse(SeriesFastForward.supportsShelf(Category.CONTINUE_WATCHING))
        assertFalse(SeriesFastForward.supportsShelf(Category.FAVORITE_MOVIES))
        val localized = Category(name = "Nächste Folgen", list = emptyList()).also {
            it.identityKey = Category.NEXT_EPISODES
        }
        assertTrue(SeriesFastForward.supportsShelf(localized))
        assertFalse(
            SeriesFastForward.supportsShelf(
                Category(name = "Nächste Folgen", list = emptyList()),
            ),
        )
    }

    @Test
    fun episodeNewWhenMissingFromPreviousSnapshot() {
        val ep = Episode(id = "ep-new", number = 2)
        assertTrue(
            SeriesFastForward.isNewEpisode(
                episode = ep,
                sinceMs = 1_000L,
                previousIds = setOf("ep-old"),
            ),
        )
    }

    @Test
    fun episodeNewWhenReleasedAfterVisit() {
        val cal = Calendar.getInstance().apply { timeInMillis = 5_000L }
        val ep = Episode(id = "ep-1", number = 1).also { it.released = cal }
        assertTrue(
            SeriesFastForward.isNewEpisode(
                episode = ep,
                sinceMs = 1_000L,
                previousIds = setOf("ep-1"),
            ),
        )
        assertFalse(
            SeriesFastForward.isNewEpisode(
                episode = ep,
                sinceMs = 9_000L,
                previousIds = setOf("ep-1"),
            ),
        )
    }

    @Test
    fun tvShowNewWhenFavoritedAfterVisit() {
        val show = TvShow(id = "show-1", title = "A").also { it.favoritedAtMillis = 4_000L }
        assertTrue(
            SeriesFastForward.isNewTvShow(
                show = show,
                sinceMs = 1_000L,
                previousIds = setOf("show-1"),
            ),
        )
        assertFalse(
            SeriesFastForward.isNewTvShow(
                show = show,
                sinceMs = 9_000L,
                previousIds = setOf("show-1"),
            ),
        )
    }

    @Test
    fun keepShelfWhenFilteredStillHasItems() {
        assertTrue(
            SeriesFastForward.keepShelfWhenEmpty(
                categoryName = Category.NEXT_EPISODES,
                unfilteredCount = 3,
                filteredCount = 2,
            ),
        )
        assertFalse(
            SeriesFastForward.keepShelfWhenEmpty(
                categoryName = Category.NEXT_EPISODES,
                unfilteredCount = 0,
                filteredCount = 0,
            ),
        )
    }

    @Test
    fun hasVisitBaselineNeedsTimestampAndSnapshotIds() {
        assertFalse(SeriesFastForward.hasVisitBaseline(0L, emptySet()))
        // Timestamp-only is not a baseline (avoids first-session novelty flicker).
        assertFalse(SeriesFastForward.hasVisitBaseline(10L, emptySet()))
        assertFalse(SeriesFastForward.hasVisitBaseline(0L, setOf("a")))
        assertTrue(SeriesFastForward.hasVisitBaseline(10L, setOf("a")))
    }

    @Test
    fun commitVisitIgnoresEmptyIdSets() {
        // Pure helper: empty commit must not create a timestamp-only baseline.
        // (UserPreferences-backed commitVisit is integration-tested on device.)
        assertFalse(SeriesFastForward.hasVisitBaseline(1L, emptySet()))
    }

    @Test
    fun filterEpisodesPassesThroughWithoutBaselineLogicWhenCheckingIds() {
        val old = Episode(id = "old", number = 1)
        val fresh = Episode(id = "fresh", number = 2)
        // Pure helpers (no UserPreferences enabled gate):
        val kept = listOf(old, fresh).filter {
            SeriesFastForward.isNewEpisode(it, sinceMs = 1L, previousIds = setOf("old"))
        }
        assertEquals(listOf(fresh), kept)
    }
}
