package com.betterstreamflix.app.home

import com.betterstreamflix.app.models.Category
import com.betterstreamflix.app.models.Movie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeShelfLayoutTest {

    private fun shelf(name: String, id: String = name): Category =
        Category(
            name = name,
            list = listOf(Movie(id = "$name-1", title = name, poster = null)),
        ).also { it.identityKey = id }

    @Test
    fun apply_afterRemix_floatsPinsUnderFeatured() {
        val featured = shelf(Category.FEATURED)
        val continueWatching = shelf(Category.CONTINUE_WATCHING)
        val live = shelf(Category.HOME_LIVE, Category.HOME_LIVE_ID)
        val downloads = shelf(Category.DOWNLOADS, Category.DOWNLOADS_ID)
        val chill = shelf(Category.SMART_CHILL_EVENING)
        val favorites = shelf(Category.FAVORITE_MOVIES)

        // Incoming order already Remix-shaped: Continue → Live → Collections → rest.
        val remixed = listOf(featured, continueWatching, live, chill, downloads, favorites)
        val ordered = HomeShelfLayout.apply(
            categories = remixed,
            pins = listOf(Category.DOWNLOADS_ID, Category.HOME_LIVE_ID),
        )

        assertEquals(
            listOf(
                Category.FEATURED,
                Category.DOWNLOADS_ID,
                Category.HOME_LIVE_ID,
                Category.CONTINUE_WATCHING,
                Category.SMART_CHILL_EVENING,
                Category.FAVORITE_MOVIES,
            ),
            ordered.map { HomeShelfLayout.shelfId(it) },
        )
    }

    @Test
    fun apply_capsPinsAtThreeAndSkipsMissing() {
        val featured = shelf(Category.FEATURED)
        val a = shelf(Category.CONTINUE_WATCHING)
        val b = shelf(Category.HOME_LIVE, Category.HOME_LIVE_ID)
        val c = shelf(Category.DOWNLOADS, Category.DOWNLOADS_ID)
        val d = shelf(Category.NEXT_EPISODES)

        val ordered = HomeShelfLayout.apply(
            categories = listOf(featured, a, b, c, d),
            pins = listOf(
                Category.HOME_LIVE_ID,
                "missing",
                Category.DOWNLOADS_ID,
                Category.CONTINUE_WATCHING,
                Category.NEXT_EPISODES,
            ),
        )

        assertEquals(
            listOf(
                Category.FEATURED,
                Category.HOME_LIVE_ID,
                Category.DOWNLOADS_ID,
                Category.CONTINUE_WATCHING,
                Category.NEXT_EPISODES,
            ),
            ordered.map { HomeShelfLayout.shelfId(it) },
        )
    }

    @Test
    fun featured_isNotPinnable() {
        assertFalse(HomeShelfLayout.isPinnable(shelf(Category.FEATURED)))
        assertTrue(HomeShelfLayout.isPinnable(shelf(Category.CONTINUE_WATCHING)))
    }
}
