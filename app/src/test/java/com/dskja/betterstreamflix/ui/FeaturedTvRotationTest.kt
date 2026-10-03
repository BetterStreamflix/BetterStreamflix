package com.dskja.betterstreamflix.ui

import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeaturedTvRotationTest {

    @Test
    fun coerceIndexClampsAndHandlesEmpty() {
        assertEquals(0, FeaturedTvRotation.coerceIndex(-1, 0))
        assertEquals(0, FeaturedTvRotation.coerceIndex(5, 0))
        assertEquals(0, FeaturedTvRotation.coerceIndex(-3, 3))
        assertEquals(2, FeaturedTvRotation.coerceIndex(99, 3))
        assertEquals(1, FeaturedTvRotation.coerceIndex(1, 3))
    }

    @Test
    fun nextIndexWrapsAndIsStableForSingletons() {
        assertEquals(0, FeaturedTvRotation.nextIndex(0, 0))
        assertEquals(0, FeaturedTvRotation.nextIndex(0, 1))
        assertEquals(0, FeaturedTvRotation.nextIndex(7, 1))
        assertEquals(1, FeaturedTvRotation.nextIndex(0, 3))
        assertEquals(2, FeaturedTvRotation.nextIndex(1, 3))
        assertEquals(0, FeaturedTvRotation.nextIndex(2, 3))
        // Out-of-range selectedIndex is coerced before advance.
        assertEquals(1, FeaturedTvRotation.nextIndex(-1, 3))
        assertEquals(0, FeaturedTvRotation.nextIndex(99, 3))
    }

    @Test
    fun bannerAndTitlePreferShowArtwork() {
        val movie = Movie(id = "m1", title = "Movie", banner = "https://m/banner.jpg")
        val tv = TvShow(id = "t1", title = "Show", banner = "https://t/banner.jpg")
        assertEquals("https://m/banner.jpg", FeaturedTvRotation.bannerOf(movie))
        assertEquals("https://t/banner.jpg", FeaturedTvRotation.bannerOf(tv))
        assertEquals("Movie", FeaturedTvRotation.titleOf(movie))
        assertEquals("Show", FeaturedTvRotation.titleOf(tv))
        assertNull(FeaturedTvRotation.bannerOf(null))
        assertNull(FeaturedTvRotation.titleOf(null))
    }

    @Test
    fun bannerOfUpgradesTmdbSizesToOriginal() {
        val movie = Movie(
            id = "m1",
            title = "Movie",
            banner = "https://image.tmdb.org/t/p/w500/banner.jpg",
            poster = "https://image.tmdb.org/t/p/w185/poster.jpg",
        )
        assertEquals(
            "https://image.tmdb.org/t/p/original/banner.jpg",
            FeaturedTvRotation.bannerOf(movie),
        )
        val posterOnly = TvShow(
            id = "t1",
            title = "Show",
            poster = "https://image.tmdb.org/t/p/w342/poster.jpg",
        )
        assertEquals(
            "https://image.tmdb.org/t/p/original/poster.jpg",
            FeaturedTvRotation.bannerOf(posterOnly),
        )
    }

    @Test
    fun rotationSequenceCyclesThroughAllItems() {
        val size = 4
        var index = 0
        val seen = mutableListOf<Int>()
        repeat(size) {
            index = FeaturedTvRotation.nextIndex(index, size)
            seen += index
        }
        assertEquals(listOf(1, 2, 3, 0), seen)
    }
}
