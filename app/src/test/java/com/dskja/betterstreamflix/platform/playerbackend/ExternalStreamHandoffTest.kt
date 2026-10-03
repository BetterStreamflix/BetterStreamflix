package com.dskja.betterstreamflix.platform.playerbackend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalStreamHandoffTest {

    @Test
    fun extractUrlFromPlaylist_skipsComments() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            https://cdn.example/master.m3u8
        """.trimIndent()
        assertEquals(
            "https://cdn.example/master.m3u8",
            ExternalStreamHandoff.extractUrlFromPlaylist(playlist),
        )
    }

    @Test
    fun extractUrlFromPlaylist_returnsNullWhenEmpty() {
        assertNull(ExternalStreamHandoff.extractUrlFromPlaylist("#EXTM3U\n#EXT-X-ENDLIST\n"))
    }

    @Test
    fun extractUrlFromPlaylist_ignoresRelativeSegments() {
        val playlist = """
            #EXTM3U
            segment001.ts
            https://cdn.example/real.m3u8
        """.trimIndent()
        assertEquals(
            "https://cdn.example/real.m3u8",
            ExternalStreamHandoff.extractUrlFromPlaylist(playlist),
        )
    }

    @Test
    fun extractUrlFromPlaylist_returnsNullForRelativeOnly() {
        assertNull(ExternalStreamHandoff.extractUrlFromPlaylist("#EXTM3U\nsegment.ts\n"))
    }

    @Test
    fun headerArray_flattensKeyValuePairsForMx() {
        val headers = mapOf(
            "Referer" to "https://example.com/",
            "User-Agent" to "BetterStreamflix",
        )
        val headerArray = headers.flatMap { listOf(it.key, it.value) }
        assertEquals(listOf("Referer", "https://example.com/", "User-Agent", "BetterStreamflix"), headerArray)
    }

    @Test
    fun mpvHeaderLines_useLfAndReferrerSpelling() {
        val headers = mapOf("Referer" to "https://ref.example/", "Cookie" to "a=1")
        val headerLinesLf = headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        assertTrue(headerLinesLf.contains("Referer: https://ref.example/"))
        assertTrue(headerLinesLf.contains("Cookie: a=1"))
        assertTrue(!headerLinesLf.contains("\r"))
        val referer = headers.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
        assertEquals("https://ref.example/", referer)
    }

    @Test
    fun decodeBase64Uri_returnsNullOnMalformed() {
        assertNull(ExternalStreamHandoff.decodeBase64Uri("not-a-data-uri"))
        assertNull(ExternalStreamHandoff.decodeBase64Uri("data:text/plain;base64"))
    }
}
