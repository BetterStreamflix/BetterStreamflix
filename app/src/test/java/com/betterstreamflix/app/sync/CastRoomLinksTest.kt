package com.betterstreamflix.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastRoomLinksTest {

    @Test
    fun buildAndParseRoundTrip() {
        val token = "abcdef12abcdef12abcdef12abcdef12"
        val uri = CastRoomLinks.buildUri(token)
        assertTrue(uri.startsWith("https://betterstreamflix.app/cast/"))
        assertEquals(token, CastRoomLinks.parseToken(uri))
    }

    @Test
    fun parseCustomSchemeAndHttps() {
        val token = "abcdef12abcdef12"
        assertEquals(token, CastRoomLinks.parseToken("betterstreamflix://cast?token=$token"))
        assertEquals(token, CastRoomLinks.parseToken(CastRoomLinks.buildCustomSchemeUri(token)))
        assertEquals(token, CastRoomLinks.parseToken("https://betterstreamflix.app/cast/$token"))
        assertEquals(
            token,
            CastRoomLinks.parseToken("https://www.betterstreamflix.app/cast?token=$token"),
        )
    }

    @Test
    fun parseLegacyStreamflixScheme() {
        assertEquals(
            "abcdef12abcdef12",
            CastRoomLinks.parseToken("streamflix://cast/abcdef12abcdef12"),
        )
    }

    @Test
    fun rejectsPartyAndForeignLinks() {
        assertNull(CastRoomLinks.parseToken("betterstreamflix://cast/short"))
        assertNull(CastRoomLinks.parseToken("betterstreamflix://party/abcdef12abcdef12"))
        assertNull(CastRoomLinks.parseToken("https://example.com/cast/abcdef12abcdef12"))
    }

    @Test
    fun shortCodeNormalization() {
        assertEquals("ABC234", CastRoomLinks.normalizeShortCode("abc234"))
        // Ambiguous glyphs map into generator alphabet (0/O→Q, 1/I/L→J).
        assertEquals("ABCQJ2", CastRoomLinks.normalizeShortCode("abc012"))
        assertEquals("ABC234", CastRoomLinks.normalizeShortCode("ABC-234"))
        assertEquals("ABC234", CastRoomLinks.normalizeShortCode(" abc 234 "))
        assertNull(CastRoomLinks.normalizeShortCode("short"))
        assertNull(CastRoomLinks.normalizeShortCode("HAS!@#"))
        assertTrue(CastRoomLinks.isValidShortCode("XK9M2P"))
        assertFalse(CastRoomLinks.isValidShortCode("abc"))
        // O/I/L never survive normalize into invalid generator letters.
        assertEquals("QJJ234", CastRoomLinks.normalizeShortCode("OIL234"))
    }

    @Test
    fun queueHelpers() {
        val items = listOf(
            CastRoomItemPayload(id = "a", sortOrder = 0, mediaType = "movie", mediaId = "1", title = "One"),
            CastRoomItemPayload(id = "b", sortOrder = 1, mediaType = "movie", mediaId = "2", title = "Two"),
            CastRoomItemPayload(id = "c", sortOrder = 2, mediaType = "movie", mediaId = "3", title = "Three"),
        )
        val payload = CastRoomPayload(
            provider = "test",
            nowPlayingIndex = 1,
            items = items,
        )
        assertEquals("Two", CastRoomQueue.nowPlaying(payload)?.title)
        assertEquals("Three", CastRoomQueue.upNext(payload)?.title)
        assertEquals(listOf("a", "c", "b"), CastRoomQueue.moveItem(listOf("a", "b", "c"), 1, 2))
        assertNull(CastRoomQueue.moveItem(listOf("a"), 0, 0))
        assertEquals(6, CastRoomQueue.newShortCode().length)
        assertTrue(CastRoomLinks.isValidShortCode(CastRoomQueue.newShortCode()))
    }
}
