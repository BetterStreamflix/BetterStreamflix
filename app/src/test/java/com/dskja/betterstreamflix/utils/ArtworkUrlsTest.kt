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
    fun preferFeaturedUsesOriginal() {
        assertEquals(
            "https://image.tmdb.org/t/p/original/abc.jpg",
            ArtworkUrls.preferFeatured("https://image.tmdb.org/t/p/w300/abc.jpg"),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/original/abc.jpg",
            ArtworkUrls.preferFeatured("https://image.tmdb.org/t/p/w1280/abc.jpg"),
        )
    }

    @Test
    fun preferFeaturedUpgradesLegacyBestv2Size() {
        assertEquals(
            "https://image.tmdb.org/t/p/original/abc.jpg",
            ArtworkUrls.preferFeatured(
                "https://image.tmdb.org/t/p/w185_and_h278_bestv2/abc.jpg",
            ),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w780/abc.jpg",
            ArtworkUrls.preferPoster(
                "https://image.tmdb.org/t/p/w92_and_h138_face/abc.jpg",
            ),
        )
    }

    @Test
    fun preferPosterUsesW780() {
        assertEquals(
            "https://image.tmdb.org/t/p/w780/poster.jpg",
            ArtworkUrls.preferPoster("https://image.tmdb.org/t/p/w185/poster.jpg"),
        )
    }

    @Test
    fun featuredBannerOrPosterPrefersOriginalBanner() {
        assertEquals(
            "https://image.tmdb.org/t/p/original/banner.jpg",
            ArtworkUrls.featuredBannerOrPoster(
                banner = "https://image.tmdb.org/t/p/w500/banner.jpg",
                poster = "https://image.tmdb.org/t/p/w500/poster.jpg",
            ),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/original/poster.jpg",
            ArtworkUrls.featuredBannerOrPoster(
                banner = null,
                poster = "https://image.tmdb.org/t/p/w342/poster.jpg",
            ),
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
    fun preferLogoPrefersOriginalThenHero() {
        assertEquals(
            "https://image.tmdb.org/t/p/original/logo.png",
            ArtworkUrls.preferLogo("https://image.tmdb.org/t/p/w500/logo.png"),
        )
        assertEquals(
            "https://cdn.example.com/logo.png",
            ArtworkUrls.preferLogo("https://cdn.example.com/logo.png"),
        )
        assertNull(ArtworkUrls.preferLogo("  "))
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

    @Test
    fun logoFileIdentity_collapsesSizeTiers() {
        val a = ArtworkUrls.logoFileIdentity("https://image.tmdb.org/t/p/original/logo.png")
        val b = ArtworkUrls.logoFileIdentity("https://image.tmdb.org/t/p/w1280/logo.png")
        val c = ArtworkUrls.logoFileIdentity("https://image.tmdb.org/t/p/w300/logo.png")
        assertEquals(a, b)
        assertEquals(b, c)
    }
}
