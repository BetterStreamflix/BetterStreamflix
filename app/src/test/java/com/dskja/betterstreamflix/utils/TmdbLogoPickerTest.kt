package com.dskja.betterstreamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbLogoPickerTest {

    private fun normalize(value: String): String = TmdbUtils.titleNormalizer(value)

    @Test
    fun cacheKeyUsesIdAndLanguageTag() {
        assertEquals("550:en", TmdbLogoPicker.cacheKey(550, "en-US"))
        assertEquals("550:de", TmdbLogoPicker.cacheKey(550, "de"))
        assertEquals("550:", TmdbLogoPicker.cacheKey(550, null))
        assertEquals("550:", TmdbLogoPicker.cacheKey(550, ""))
    }

    @Test
    fun inflightKeySeparatesMovieAndTv() {
        assertEquals("movie:1399:en", TmdbLogoPicker.inflightKey(false, 1399, "en"))
        assertEquals("tv:1399:en", TmdbLogoPicker.inflightKey(true, 1399, "en-US"))
    }

    @Test
    fun missSentinelMapsToNull() {
        assertNull(TmdbLogoPicker.valueFromCache(TmdbLogoPicker.LOGO_MISS))
        assertNull(TmdbLogoPicker.valueFromCache(""))
        assertEquals(
            "https://image.tmdb.org/t/p/original/logo.png",
            TmdbLogoPicker.valueFromCache("https://image.tmdb.org/t/p/original/logo.png"),
        )
    }

    @Test
    fun storeValueKeepsUrlOrMissSentinel() {
        assertEquals(
            "https://image.tmdb.org/t/p/original/logo.png",
            TmdbLogoPicker.storeValue("https://image.tmdb.org/t/p/original/logo.png"),
        )
        assertEquals(TmdbLogoPicker.LOGO_MISS, TmdbLogoPicker.storeValue(null))
        assertEquals(TmdbLogoPicker.LOGO_MISS, TmdbLogoPicker.storeValue("  "))
        assertEquals(TmdbLogoPicker.LOGO_MISS, TmdbLogoPicker.storeValue(""))
    }

    @Test
    fun readCacheDistinguishesAbsentMissStaleHitAndStaleHit() {
        val now = 1_000_000L
        assertEquals(
            TmdbLogoPicker.CacheStatus.ABSENT,
            TmdbLogoPicker.readCache(null, now).status,
        )
        assertEquals(
            TmdbLogoPicker.CacheStatus.MISS,
            TmdbLogoPicker.readCache(
                TmdbLogoPicker.CacheEntry(TmdbLogoPicker.LOGO_MISS, now - 1_000L),
                now,
            ).status,
        )
        assertEquals(
            TmdbLogoPicker.CacheStatus.STALE,
            TmdbLogoPicker.readCache(
                TmdbLogoPicker.CacheEntry(
                    TmdbLogoPicker.LOGO_MISS,
                    now - TmdbLogoPicker.MISS_TTL_MS - 1,
                ),
                now,
            ).status,
        )
        val hit = TmdbLogoPicker.readCache(
            TmdbLogoPicker.CacheEntry("https://image.tmdb.org/t/p/original/x.png", now),
            now,
        )
        assertEquals(TmdbLogoPicker.CacheStatus.HIT, hit.status)
        assertEquals("https://image.tmdb.org/t/p/original/x.png", hit.url)
        assertEquals(
            TmdbLogoPicker.CacheStatus.STALE,
            TmdbLogoPicker.readCache(
                TmdbLogoPicker.CacheEntry(
                    "https://image.tmdb.org/t/p/original/x.png",
                    now - TmdbLogoPicker.HIT_TTL_MS - 1,
                ),
                now,
            ).status,
        )
    }

    @Test
    fun trustedTmdbAndUpgradeHeuristics() {
        assertTrue(
            TmdbLogoPicker.isTrustedTmdbLogo("https://image.tmdb.org/t/p/original/logo.png"),
        )
        assertFalse(TmdbLogoPicker.isTrustedTmdbLogo("https://cdn.provider.example/logo.png"))
        assertFalse(TmdbLogoPicker.isTrustedTmdbLogo(null))
        assertTrue(TmdbLogoPicker.shouldUpgradeLogo(null))
        assertTrue(TmdbLogoPicker.shouldUpgradeLogo("https://cdn.provider.example/logo.png"))
        assertFalse(
            TmdbLogoPicker.shouldUpgradeLogo("https://image.tmdb.org/t/p/w500/logo.png"),
        )
    }

    @Test
    fun shouldUpgradeLogo_onLanguageMismatch() {
        val tmdb = "https://image.tmdb.org/t/p/original/logo.png"
        assertTrue(TmdbLogoPicker.shouldUpgradeLogo(tmdb, storedLang = "en", wantedLang = "de"))
        // Null/blank stored lang must NOT force infinite re-resolve of a trusted logo.
        assertFalse(TmdbLogoPicker.shouldUpgradeLogo(tmdb, storedLang = null, wantedLang = "de"))
        assertFalse(TmdbLogoPicker.shouldUpgradeLogo(tmdb, storedLang = "de", wantedLang = "de-DE"))
        assertFalse(TmdbLogoPicker.shouldUpgradeLogo(tmdb, storedLang = null, wantedLang = null))
    }

    @Test
    fun preferResolvedLogoUpgradesProviderAssets() {
        val tmdb = "https://image.tmdb.org/t/p/original/tmdb.png"
        val provider = "https://cdn.provider.example/logo.png"
        assertEquals(tmdb, TmdbLogoPicker.preferResolvedLogo(provider, tmdb))
        assertEquals(tmdb, TmdbLogoPicker.preferResolvedLogo(null, tmdb))
        assertEquals(provider, TmdbLogoPicker.preferResolvedLogo(provider, null))
        assertEquals(
            tmdb,
            TmdbLogoPicker.preferResolvedLogo(tmdb, "https://image.tmdb.org/t/p/original/other.png"),
        )
    }

    @Test
    fun preferResolvedLogo_prefersTmdbWhenLangsDiffer() {
        val en = "https://image.tmdb.org/t/p/original/en.png"
        val de = "https://image.tmdb.org/t/p/original/de.png"
        assertEquals(
            de,
            TmdbLogoPicker.preferResolvedLogo(
                current = en,
                tmdb = de,
                currentLang = "en",
                wantedLang = "de",
            ),
        )
        assertEquals(
            en,
            TmdbLogoPicker.preferResolvedLogo(
                current = en,
                tmdb = de,
                currentLang = "en",
                wantedLang = "en-US",
            ),
        )
    }

    @Test
    fun pickBestSkipsSvgAndPrefersRequestedLanguage() {
        val logos = listOf(
            TmdbLogoPicker.LogoCandidate("/en.svg", iso639 = "en", voteCount = 999, width = 800),
            TmdbLogoPicker.LogoCandidate("/de.png", iso639 = "de", voteCount = 10, width = 600),
            TmdbLogoPicker.LogoCandidate("/en.png", iso639 = "en", voteCount = 50, width = 700),
            TmdbLogoPicker.LogoCandidate("/null.png", iso639 = null, voteCount = 80, width = 900),
        )
        assertEquals("/de.png", TmdbLogoPicker.pickBestFilePath(logos, "de-DE"))
        assertEquals("/en.png", TmdbLogoPicker.pickBestFilePath(logos, "en"))
    }

    @Test
    fun pickBest_narrowWantedLangBeatsWideEnglish() {
        assertEquals(
            "/de-narrow.png",
            TmdbLogoPicker.pickBestFilePath(
                listOf(
                    TmdbLogoPicker.LogoCandidate(
                        "/de-narrow.png",
                        iso639 = "de",
                        voteCount = 5,
                        width = 120,
                        height = 40,
                    ),
                    TmdbLogoPicker.LogoCandidate(
                        "/en-wide.png",
                        iso639 = "en",
                        voteCount = 99,
                        width = 900,
                        height = 200,
                    ),
                ),
                "de",
            ),
        )
    }

    @Test
    fun pickBestFallsBackToEnglishThenLanguageLessThenVotes() {
        val logos = listOf(
            TmdbLogoPicker.LogoCandidate("/fr.png", iso639 = "fr", voteCount = 5, width = 400),
            TmdbLogoPicker.LogoCandidate("/null.png", iso639 = null, voteCount = 40, width = 400),
            TmdbLogoPicker.LogoCandidate("/en.png", iso639 = "en", voteCount = 20, width = 400),
        )
        assertEquals("/en.png", TmdbLogoPicker.pickBestFilePath(logos, "ja"))
        assertEquals(
            "/null.png",
            TmdbLogoPicker.pickBestFilePath(
                listOf(
                    TmdbLogoPicker.LogoCandidate("/fr.png", iso639 = "fr", voteCount = 5, width = 400),
                    TmdbLogoPicker.LogoCandidate("/null.png", iso639 = null, voteCount = 40, width = 400),
                ),
                "ja",
            ),
        )
        assertEquals(
            "/fr-hi.png",
            TmdbLogoPicker.pickBestFilePath(
                listOf(
                    TmdbLogoPicker.LogoCandidate("/fr-lo.png", iso639 = "fr", voteCount = 1, width = 400),
                    TmdbLogoPicker.LogoCandidate("/fr-hi.png", iso639 = "fr", voteCount = 9, width = 400),
                ),
                "ja",
            ),
        )
    }

    @Test
    fun pickBestDropsTinyWhenWiderExists() {
        assertEquals(
            "/wide.png",
            TmdbLogoPicker.pickBestFilePath(
                listOf(
                    TmdbLogoPicker.LogoCandidate("/tiny.png", iso639 = "en", voteCount = 999, width = 64),
                    TmdbLogoPicker.LogoCandidate("/wide.png", iso639 = "en", voteCount = 10, width = 800),
                ),
                "en",
            ),
        )
    }

    @Test
    fun pickBestReturnsNullForEmptyOrSvgOnly() {
        assertNull(TmdbLogoPicker.pickBestFilePath(null, "en"))
        assertNull(TmdbLogoPicker.pickBestFilePath(emptyList(), "en"))
        assertNull(
            TmdbLogoPicker.pickBestFilePath(
                listOf(TmdbLogoPicker.LogoCandidate("/x.svg", iso639 = "en", voteCount = 100, width = 800)),
                "en",
            ),
        )
    }

    @Test
    fun searchHitRequiresVotesOrPopularityAndTitleSimilarity() {
        assertTrue(
            TmdbLogoPicker.isAcceptableSearchHit(
                voteCount = 50,
                popularity = 1f,
                queryTitle = "Fight Club",
                candidateTitles = listOf("Fight Club"),
                normalize = ::normalize,
            ),
        )
        assertTrue(
            TmdbLogoPicker.isAcceptableSearchHit(
                voteCount = 5,
                popularity = 25f,
                queryTitle = "Breaking Bad",
                candidateTitles = listOf("Breaking Bad"),
                normalize = ::normalize,
            ),
        )
        assertFalse(
            TmdbLogoPicker.isAcceptableSearchHit(
                voteCount = 10,
                popularity = 5f,
                queryTitle = "Fight Club",
                candidateTitles = listOf("Fight Club"),
                normalize = ::normalize,
            ),
        )
        assertFalse(
            TmdbLogoPicker.isAcceptableSearchHit(
                voteCount = 500,
                popularity = 80f,
                queryTitle = "Fight Club",
                candidateTitles = listOf("Completely Different"),
                normalize = ::normalize,
            ),
        )
    }

    @Test
    fun titleSimilarityUsesPrefixAndLeadTokenNotBareContains() {
        assertTrue(
            TmdbLogoPicker.titlesSimilarEnough(
                "The Matrix Reloaded",
                listOf("Matrix Reloaded"),
                ::normalize,
            ),
        )
        assertTrue(
            TmdbLogoPicker.titlesSimilarEnough(
                "Breaking",
                listOf("Breaking Bad"),
                ::normalize,
            ),
        )
        assertFalse(
            TmdbLogoPicker.titlesSimilarEnough(
                "ab",
                listOf("ab cd"),
                ::normalize,
            ),
        )
        assertFalse(
            TmdbLogoPicker.titlesSimilarEnough(
                "Fight Club",
                listOf("xyz"),
                ::normalize,
            ),
        )
        // Bare substring / trailing-token matches must not pass.
        assertFalse(
            TmdbLogoPicker.titlesSimilarEnough(
                "Ring",
                listOf("Lord of the Rings"),
                ::normalize,
            ),
        )
        assertFalse(
            TmdbLogoPicker.titlesSimilarEnough(
                "Bad",
                listOf("Breaking Bad"),
                ::normalize,
            ),
        )
    }
}
