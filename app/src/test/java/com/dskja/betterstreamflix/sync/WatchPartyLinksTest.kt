package com.dskja.betterstreamflix.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchPartyLinksTest {

    @Test
    fun buildAndParseRoundTrip() {
        val token = "abcdef12abcdef12abcdef12abcdef12"
        val uri = WatchPartyLinks.buildUri(token)
        assertTrue(uri.startsWith("https://betterstreamflix.app/party/"))
        assertEquals(token, WatchPartyLinks.parseToken(uri))
    }

    @Test
    fun parseCustomSchemeAndHttps() {
        val token = "abcdef12abcdef12"
        assertEquals(token, WatchPartyLinks.parseToken("betterstreamflix://party?token=$token"))
        assertEquals(token, WatchPartyLinks.parseToken(WatchPartyLinks.buildCustomSchemeUri(token)))
        assertEquals(token, WatchPartyLinks.parseToken("https://betterstreamflix.app/party/$token"))
        assertEquals(
            token,
            WatchPartyLinks.parseToken("https://www.betterstreamflix.app/party?token=$token"),
        )
    }

    @Test
    fun parseLegacyStreamflixScheme() {
        assertEquals(
            "abcdef12abcdef12",
            WatchPartyLinks.parseToken("streamflix://party/abcdef12abcdef12"),
        )
    }

    @Test
    fun rejectsShortOrForeignLinks() {
        assertNull(WatchPartyLinks.parseToken("betterstreamflix://party/short"))
        assertNull(WatchPartyLinks.parseToken("betterstreamflix://list/abcdef12abcdef12"))
        assertNull(WatchPartyLinks.parseToken("https://example.com/party/abcdef12abcdef12"))
    }

    @Test
    fun tokenCharsetAndLength() {
        assertTrue(WatchPartyLinks.isValidToken("abcd_efg-hijK"))
        assertFalse(WatchPartyLinks.isValidToken("short"))
        assertFalse(WatchPartyLinks.isValidToken("has space!!"))
        assertFalse(WatchPartyLinks.isValidToken("a".repeat(129)))
        assertTrue(WatchPartyLinks.isValidToken("a".repeat(128)))
    }

    @Test
    fun seekThreshold() {
        assertTrue(WatchPartySync.shouldSeek(0L, 2_500L))
        assertFalse(WatchPartySync.shouldSeek(1_000L, 1_500L))
    }
}
