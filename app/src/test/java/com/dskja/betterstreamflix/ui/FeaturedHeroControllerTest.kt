package com.dskja.betterstreamflix.ui

import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.WatchItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturedHeroControllerTest {

    @Test
    fun watchProgressReadsMovieAndTvEpisodeHistory() {
        val movie = Movie(id = "m", title = "M").apply {
            watchHistory = WatchItem.WatchHistory(
                lastEngagementTimeUtcMillis = 1L,
                lastPlaybackPositionMillis = 50_000,
                durationMillis = 100_000,
            )
        }
        assertEquals(50, FeaturedHeroController.watchProgress(movie).percent)

        val tv = TvShow(id = "t", title = "T")
        assertNull(FeaturedHeroController.watchProgress(tv).history)
    }

    @Test
    fun genresLineJoinsNonBlank() {
        val movie = Movie(
            id = "m",
            title = "M",
            genres = listOf(
                com.dskja.betterstreamflix.models.Genre(id = "1", name = "Action"),
                com.dskja.betterstreamflix.models.Genre(id = "2", name = " "),
                com.dskja.betterstreamflix.models.Genre(id = "3", name = "Drama"),
            ),
        )
        assertEquals("Action, Drama", FeaturedHeroController.genresLine(movie))
    }

    @Test
    fun impressionsIncrement() {
        FeaturedHeroController.resetImpressions()
        FeaturedHeroController.recordImpression(Movie(id = "1", title = "One"))
        FeaturedHeroController.recordImpression(null)
        FeaturedHeroController.recordImpression(TvShow(id = "2", title = "Two"))
        assertEquals(2, FeaturedHeroController.impressionCount())
        assertTrue(FeaturedHeroController.MAX_FEATURED_BITMAP_BUDGET >= 12)
    }

    @Test
    fun categoryCopyPreservesSelectedIndex() {
        val cat = com.dskja.betterstreamflix.models.Category(
            name = com.dskja.betterstreamflix.models.Category.FEATURED,
            list = listOf(Movie(id = "1", title = "A")),
        )
        cat.selectedIndex = 3
        cat.itemSpacing = 12
        val copy = cat.copy(list = cat.list)
        assertEquals(3, copy.selectedIndex)
        assertEquals(12, copy.itemSpacing)
    }
}
