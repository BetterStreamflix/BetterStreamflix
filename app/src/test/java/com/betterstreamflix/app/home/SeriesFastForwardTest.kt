package com.betterstreamflix.app.home

import com.betterstreamflix.app.models.Category
import com.betterstreamflix.app.models.Episode
import com.betterstreamflix.app.models.TvShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Fast-Forward UI was removed; helpers stay as no-ops / pure predicates.
 */
class SeriesFastForwardTest {

    @Test
    fun supportsShelfAlwaysFalseAfterRemoval() {
        assertFalse(SeriesFastForward.supportsShelf(Category.NEXT_EPISODES))
        assertFalse(SeriesFastForward.supportsShelf(Category.FAVORITE_TV_SHOWS))
        assertFalse(SeriesFastForward.isEnabled())
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
    fun filterEpisodesPassesThroughWhenDisabled() {
        val list = listOf(Episode(id = "a", number = 1), Episode(id = "b", number = 2))
        assertEquals(list, SeriesFastForward.filterEpisodes(list))
    }
}
