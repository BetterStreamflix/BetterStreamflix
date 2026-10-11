package com.dskja.betterstreamflix.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedWatchlistLinksTest {

    @Test
    fun buildAndParseRoundTrip() {
        val token = "abcdef12abcdef12abcdef12abcdef12"
        assertEquals(token, SharedWatchlistLinks.parseToken(SharedWatchlistLinks.buildUri(token)))
    }

    @Test
    fun parseQueryToken() {
        assertEquals(
            "abcdef12abcdef12",
            SharedWatchlistLinks.parseToken("betterstreamflix://list?token=abcdef12abcdef12"),
        )
    }

    @Test
    fun rejectsShortOrForeignLinks() {
        assertNull(SharedWatchlistLinks.parseToken("betterstreamflix://list/short"))
        assertNull(SharedWatchlistLinks.parseToken("betterstreamflix://resolve?x=1"))
        assertNull(SharedWatchlistLinks.parseToken("https://example.com/list/abcdef12abcdef12"))
    }
}
