package com.dskja.betterstreamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkUrlsTest {

    @Test
    fun preferHeroUpgradesThumbnailSizes() {
        assertEquals(
            "https://image.tmdb.org/t/p/w1280/abc.jpg",
            ArtworkUrls.preferHero("https://image.tmdb.org/t/p/w300/abc.jpg"),
        )
    }

    @Test
    fun preferOriginalUpgradesThumbnailSizes() {
        assertEquals(
            "https://image.tmdb.org/t/p/original/abc.jpg",
            ArtworkUrls.preferOriginal("https://image.tmdb.org/t/p/w780/abc.jpg"),
        )
    }

    @Test
    fun nonTmdbUrlsAreLeftAlone() {
        val url = "https://cdn.example.com/art/banner.jpg"
        assertEquals(url, ArtworkUrls.preferHero(url))
    }

    @Test
    fun blankUrlsBecomeNull() {
        assertNull(ArtworkUrls.preferHero("   "))
        assertNull(ArtworkUrls.preferOriginal(null))
    }

    @Test
    fun bannerWinsOverPosterAndFallsBackWhenMissing() {
        assertEquals(
            "https://image.tmdb.org/t/p/w1280/banner.jpg",
            ArtworkUrls.bannerOrPoster(
                banner = "https://image.tmdb.org/t/p/w500/banner.jpg",
                poster = "https://image.tmdb.org/t/p/w500/poster.jpg",
            ),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w1280/poster.jpg",
            ArtworkUrls.bannerOrPoster(
                banner = null,
                poster = "https://image.tmdb.org/t/p/w500/poster.jpg",
            ),
        )
    }
}
