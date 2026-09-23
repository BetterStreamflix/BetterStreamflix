package com.dskja.betterstreamflix.logo

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Fake-TMDb integration coverage for the logo fetch → pick → cache path
 * (resolve/getMovieLogo seam via [TmdbLogoFetch]).
 */
class TmdbLogoFetchIntegrationTest {

    @Before
    fun setUp() {
        TmdbLogoCache.clearAll()
        TmdbLogoTelemetry.reset()
    }

    @After
    fun tearDown() {
        TmdbLogoFetch.resetToDefaults()
        TmdbLogoCache.clearAll()
    }

    @Test
    fun pickUrl_skipsBlacklistedAndChoosesAlternate() {
        val logos = listOf(
            TmdbLogoPicker.LogoCandidate("/a.png", iso639 = "en", voteCount = 100, width = 600, height = 200),
            TmdbLogoPicker.LogoCandidate("/b.png", iso639 = "en", voteCount = 90, width = 600, height = 200),
        )
        TmdbLogoCache.blacklistUrl("https://image.tmdb.org/t/p/original/a.png")
        val url = TmdbLogoFetch.pickUrl(logos, "en")
        assertEquals("https://image.tmdb.org/t/p/original/b.png", url)
    }

    @Test
    fun pickUrl_emptyLogosReturnsNull() {
        assertNull(TmdbLogoFetch.pickUrl(emptyList(), "en"))
        assertNull(TmdbLogoFetch.pickUrl(null, "de"))
    }

    @Test
    fun fakeFetcher_returnsInjectedCandidates() = runBlocking {
        TmdbLogoFetch.fetchMovieLogos = { _, _ ->
            listOf(
                TmdbLogoPicker.LogoCandidate("/fake.png", iso639 = "de", voteCount = 10, width = 800, height = 200),
            )
        }
        val logos = TmdbLogoFetch.fetchMovieLogos(1, "de,en,null")
        assertEquals("/fake.png", logos?.single()?.filePath)
        assertEquals(
            "https://image.tmdb.org/t/p/original/fake.png",
            TmdbLogoFetch.pickUrl(logos, "de"),
        )
    }

    @Test
    fun decodeFailThenPick_selectsNextCandidate() {
        val logos = listOf(
            TmdbLogoPicker.LogoCandidate("/broken.png", iso639 = "en", voteCount = 200, width = 700, height = 200),
            TmdbLogoPicker.LogoCandidate("/ok.png", iso639 = "en", voteCount = 50, width = 700, height = 200),
        )
        val first = TmdbLogoFetch.pickUrl(logos, "en")
        assertEquals("https://image.tmdb.org/t/p/original/broken.png", first)
        TmdbLogoCache.markDecodeFailed(first)
        val second = TmdbLogoFetch.pickUrl(logos, "en")
        assertEquals("https://image.tmdb.org/t/p/original/ok.png", second)
    }

    @Test
    fun shouldRememberMiss_onlyForEmptyLogoLists() {
        assertTrue(TmdbLogoFetch.shouldRememberMiss(null))
        assertTrue(TmdbLogoFetch.shouldRememberMiss(emptyList()))
        assertFalse(
            TmdbLogoFetch.shouldRememberMiss(
                listOf(
                    TmdbLogoPicker.LogoCandidate("/x.png", iso639 = "en", voteCount = 1, width = 400, height = 100),
                ),
            ),
        )
    }
}
