package com.dskja.betterstreamflix.logo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TmdbLogoCacheBlacklistTest {

    @Before
    fun clear() {
        TmdbLogoCache.clearAll()
    }

    @Test
    fun blacklistBasenameBoundary_doesNotMatchSibling() {
        TmdbLogoCache.blacklistUrl("https://image.tmdb.org/t/p/original/1.png")
        assertTrue(TmdbLogoCache.isFilePathBlacklisted("/1.png"))
        assertTrue(TmdbLogoCache.isFilePathBlacklisted("1.png"))
        assertFalse(TmdbLogoCache.isFilePathBlacklisted("/11.png"))
        assertFalse(TmdbLogoCache.isFilePathBlacklisted("/21.png"))
    }

    @Test
    fun blacklistedHitAllowsAlternateFetch() {
        val key = "550:en"
        val url = "https://image.tmdb.org/t/p/original/broken.png"
        TmdbLogoCache.put(key, url)
        TmdbLogoCache.blacklistUrl(url)

        assertEquals(TmdbLogoCache.Lookup.Fetch, TmdbLogoCache.lookup(key))
        assertTrue(TmdbLogoCache.isBlacklisted(url))
    }

    @Test
    fun putRejectsBlacklistedUrlAsMiss() {
        val key = "551:en"
        val url = "https://image.tmdb.org/t/p/original/x.png"
        TmdbLogoCache.blacklistUrl(url)
        TmdbLogoCache.put(key, url)
        assertEquals(TmdbLogoCache.Lookup.KnownMiss, TmdbLogoCache.lookup(key))
    }

    @Test
    fun markDecodeFailed_dropsEntryAndBlacklistsIdentity() {
        val key = "552:en"
        val original = "https://image.tmdb.org/t/p/original/y.png"
        val w1280 = "https://image.tmdb.org/t/p/w1280/y.png"
        TmdbLogoCache.put(key, original)
        TmdbLogoCache.markDecodeFailed(w1280)
        assertTrue(TmdbLogoCache.isBlacklisted(original))
        assertEquals(TmdbLogoCache.Lookup.Fetch, TmdbLogoCache.lookup(key))
    }
}
