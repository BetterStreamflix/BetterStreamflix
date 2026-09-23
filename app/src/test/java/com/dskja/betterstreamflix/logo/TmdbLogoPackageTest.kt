package com.dskja.betterstreamflix.logo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbLogoPackageTest {

    @Test
    fun languageKey_keepsRegion() {
        assertEquals("pt-br", TmdbLogoPicker.languageKey("pt-BR"))
        assertEquals("pt-pt", TmdbLogoPicker.languageKey("pt_PT"))
        assertEquals("", TmdbLogoPicker.languageKey(null))
    }

    @Test
    fun cacheKey_usesPrimaryLanguage() {
        assertEquals("550:pt", TmdbLogoPicker.cacheKey(550, "pt-BR"))
        assertEquals("550:de", TmdbLogoPicker.cacheKey(550, "de"))
    }

    @Test
    fun primaryLanguage_alignsWithCache() {
        assertEquals("pt", TmdbLogoPicker.primaryLanguage("pt-BR"))
        assertEquals("en", TmdbLogoPicker.primaryLanguage("en-US"))
        assertEquals("", TmdbLogoPicker.primaryLanguage(null))
    }

    @Test
    fun pickBest_skipsExcludedPaths() {
        val logos = listOf(
            TmdbLogoPicker.LogoCandidate("/broken.png", iso639 = "en", voteCount = 100, width = 600, height = 200),
            TmdbLogoPicker.LogoCandidate("/good.png", iso639 = "en", voteCount = 50, width = 600, height = 200),
        )
        assertEquals(
            "/good.png",
            TmdbLogoPicker.pickBestFilePath(logos, "en", excludedFilePaths = setOf("/broken.png")),
        )
    }

    @Test
    fun pickBest_prefersWordmarkAspect() {
        val logos = listOf(
            TmdbLogoPicker.LogoCandidate("/icon.png", iso639 = "en", voteCount = 100, width = 200, height = 200),
            TmdbLogoPicker.LogoCandidate("/word.png", iso639 = "en", voteCount = 90, width = 600, height = 200),
        )
        assertEquals("/word.png", TmdbLogoPicker.pickBestFilePath(logos, "en"))
    }

    @Test
    fun fuzzyCloseEnough_acceptsNearMiss() {
        assertTrue(TmdbLogoPicker.fuzzyCloseEnough("primate", "primatte"))
        assertFalse(TmdbLogoPicker.fuzzyCloseEnough("primate", "completely"))
    }

    @Test
    fun yearGate_rejectsRemake() {
        assertFalse(
            TmdbLogoPicker.isAcceptableSearchHit(
                voteCount = 500,
                popularity = 80f,
                queryTitle = "Dune",
                candidateTitles = listOf("Dune"),
                normalize = { it.lowercase() },
                releaseYear = 2021,
                candidateYear = 1984,
            ),
        )
    }

    @Test
    fun trust_acceptsMirrorHost() {
        assertTrue(
            TmdbLogoPicker.isTrustedTmdbLogo("https://www.themoviedb.org/t/p/original/x.png"),
        )
    }

    @Test
    fun inferSource() {
        assertEquals(LogoSource.TMDB, TmdbLogoPicker.inferSource("https://image.tmdb.org/t/p/original/a.png"))
        assertEquals(LogoSource.PROVIDER, TmdbLogoPicker.inferSource("https://cdn.example/logo.png"))
        assertEquals(LogoSource.UNKNOWN, TmdbLogoPicker.inferSource(null))
    }

    @Test
    fun readCache_hitAndMissTtl() {
        val now = 1_000_000L
        assertEquals(
            TmdbLogoPicker.CacheStatus.MISS,
            TmdbLogoPicker.readCache(
                TmdbLogoPicker.CacheEntry(TmdbLogoPicker.LOGO_MISS, now - 1_000L),
                now,
            ).status,
        )
        assertNull(
            TmdbLogoPicker.readCache(
                TmdbLogoPicker.CacheEntry("https://image.tmdb.org/t/p/original/x.png", now),
                now,
            ).let { if (it.status == TmdbLogoPicker.CacheStatus.HIT) null else it.url },
        )
        assertEquals(
            "https://image.tmdb.org/t/p/original/x.png",
            TmdbLogoPicker.readCache(
                TmdbLogoPicker.CacheEntry("https://image.tmdb.org/t/p/original/x.png", now),
                now,
            ).url,
        )
    }

    @Test
    fun telemetry_records() {
        TmdbLogoTelemetry.reset()
        TmdbLogoTelemetry.recordCacheHit()
        TmdbLogoTelemetry.recordResolve()
        val snap = TmdbLogoTelemetry.snapshot()
        assertEquals(1, snap.cacheHits)
        assertEquals(1, snap.resolves)
        assertTrue(TmdbLogoTelemetry.debugSummary().contains("hits=1"))
    }

    @Test
    fun searchGate_corpus_blocksWrongPopularHits() {
        fun ok(query: String, candidate: String) = TmdbLogoPicker.isAcceptableSearchHit(
            voteCount = 500,
            popularity = 90f,
            queryTitle = query,
            candidateTitles = listOf(candidate),
            normalize = {
                it.lowercase()
                    .replace(Regex("[^a-z0-9 ]"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            },
        )
        assertFalse(ok("one last stick", "the last of us"))
        assertFalse(ok("primate", "primates of madagascar"))
        assertTrue(ok("dune part two", "dune part two"))
        assertTrue(ok("mobland", "mobland"))
    }
}
