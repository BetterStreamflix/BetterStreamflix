package com.betterstreamflix.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedWatchlistLinksTest {

    @Test
    fun buildAndParseRoundTrip() {
        val token = "abcdef12abcdef12abcdef12abcdef12"
        val uri = SharedWatchlistLinks.buildUri(token)
        assertTrue(uri.startsWith("https://betterstreamflix.app/list/"))
        assertEquals(token, SharedWatchlistLinks.parseToken(uri))
    }

    @Test
    fun parseCustomSchemeAndHttps() {
        val token = "abcdef12abcdef12"
        assertEquals(token, SharedWatchlistLinks.parseToken("betterstreamflix://list?token=$token"))
        assertEquals(token, SharedWatchlistLinks.parseToken(SharedWatchlistLinks.buildCustomSchemeUri(token)))
        assertEquals(token, SharedWatchlistLinks.parseToken("https://betterstreamflix.app/list/$token"))
        assertEquals(
            token,
            SharedWatchlistLinks.parseToken("https://www.betterstreamflix.app/list?token=$token"),
        )
    }

    @Test
    fun parseLegacyStreamflixScheme() {
        assertEquals(
            "abcdef12abcdef12",
            SharedWatchlistLinks.parseToken("streamflix://list/abcdef12abcdef12"),
        )
    }

    @Test
    fun rejectsShortOrForeignLinks() {
        assertNull(SharedWatchlistLinks.parseToken("betterstreamflix://list/short"))
        assertNull(SharedWatchlistLinks.parseToken("betterstreamflix://resolve?x=1"))
        assertNull(SharedWatchlistLinks.parseToken("https://example.com/list/abcdef12abcdef12"))
    }

    @Test
    fun tokenCharsetAndLength() {
        assertTrue(SharedWatchlistLinks.isValidToken("abcd_efg-hijK"))
        assertFalse(SharedWatchlistLinks.isValidToken("short"))
        assertFalse(SharedWatchlistLinks.isValidToken("has space!!"))
        assertFalse(SharedWatchlistLinks.isValidToken("a".repeat(129)))
        assertTrue(SharedWatchlistLinks.isValidToken("a".repeat(128)))
    }
}
