package com.dskja.betterstreamflix.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailerCatalogTest {

    private data class FakeVideo(
        val site: String?,
        val key: String?,
        val official: Boolean,
        val type: String?,
        val publishedAt: String?,
    )

    @Test
    fun isOfficialTitle_detectsLocales() {
        assertTrue(TrailerCatalog.isOfficialTitle("Official Trailer"))
        assertTrue(TrailerCatalog.isOfficialTitle("Offizieller Trailer"))
        assertTrue(TrailerCatalog.isOfficialTitle("Bande-annonce officielle"))
        assertFalse(TrailerCatalog.isOfficialTitle("Teaser"))
    }

    @Test
    fun typeRank_ordersTrailerFirst() {
        assertEquals(0, TrailerCatalog.typeRank("Trailer"))
        assertEquals(1, TrailerCatalog.typeRank("Teaser"))
        assertEquals(2, TrailerCatalog.typeRank("Clip"))
        assertEquals(3, TrailerCatalog.typeRank("Featurette"))
    }

    @Test
    fun rankVideos_prefersOfficialYoutubeTrailer() {
        val videos = listOf(
            FakeVideo("YouTube", "aaa", false, "Teaser", "2020-01-01"),
            FakeVideo("YouTube", "bbb", true, "Trailer", "2019-01-01"),
            FakeVideo("Vimeo", "ccc", true, "Trailer", "2024-01-01"),
        )
        val ranked = TrailerCatalog.rankVideos(
            videos = videos,
            siteOf = { it.site },
            keyOf = { it.key },
            officialOf = { it.official },
            typeOf = { it.type },
            publishedAtOf = { it.publishedAt },
        )
        assertEquals("bbb", ranked.first().key)
        assertTrue(ranked.none { it.site == "Vimeo" })
    }

    @Test
    fun rankVideos_fallsBackToVimeoWhenNoYoutube() {
        val videos = listOf(
            FakeVideo("Vimeo", "v1", false, "Trailer", "2020-01-01"),
            FakeVideo("Vimeo", "v2", true, "Teaser", "2021-01-01"),
        )
        val ranked = TrailerCatalog.rankVideos(
            videos = videos,
            siteOf = { it.site },
            keyOf = { it.key },
            officialOf = { it.official },
            typeOf = { it.type },
            publishedAtOf = { it.publishedAt },
        )
        assertEquals("v2", ranked.first().key)
    }

    @Test
    fun mergeTrailers_dedupesAndPrefersOfficial() {
        val seed = listOf(
            TrailerEntry("Seed", "https://www.youtube.com/watch?v=abc123", "Trailer", official = false),
        )
        val remote = listOf(
            TrailerEntry("Official Trailer", "https://www.youtube.com/watch?v=abc123", "Trailer", official = true),
            TrailerEntry("Teaser", "https://www.youtube.com/watch?v=def456", "Teaser", official = false),
        )
        val merged = TrailerCatalog.mergeTrailers(seed, remote)
        assertEquals(2, merged.size)
        assertTrue(merged.first().official)
        assertEquals("Official Trailer", merged.first().title)
    }

    @Test
    fun youtubeThumbUrl_respectsQuality() {
        assertEquals(
            "https://img.youtube.com/vi/abc/hqdefault.jpg",
            TrailerCatalog.youtubeThumbUrl("abc", TrailerCatalog.THUMB_QUALITY_DEFAULT),
        )
        assertEquals(
            "https://img.youtube.com/vi/abc/maxresdefault.jpg",
            TrailerCatalog.youtubeThumbUrl("abc", TrailerCatalog.THUMB_QUALITY_HIGH),
        )
        assertEquals(
            "https://img.youtube.com/vi/abc/sddefault.jpg",
            TrailerCatalog.youtubeThumbUrl("abc", TrailerCatalog.THUMB_QUALITY_STANDARD),
        )
    }

    @Test
    fun preferredPlayableUrl_prefersYoutubeOverVimeo() {
        val entries = listOf(
            TrailerEntry("V", "https://vimeo.com/1", site = "Vimeo"),
            TrailerEntry("Y", "https://www.youtube.com/watch?v=abc123xyz", site = "YouTube"),
        )
        assertEquals(
            "https://www.youtube.com/watch?v=abc123xyz",
            TrailerCatalog.preferredPlayableUrl(entries),
        )
    }
}
