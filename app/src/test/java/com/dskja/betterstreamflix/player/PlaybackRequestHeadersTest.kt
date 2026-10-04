package com.dskja.betterstreamflix.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRequestHeadersTest {

    @Test
    fun replacesOkHttpUserAgent() {
        val headers = PlaybackRequestHeaders.build(
            fallbackUserAgent = "BrowserUA",
            videoHeaders = mapOf("User-Agent" to "okhttp/4.12.0"),
            pageUrl = "https://embed.example/watch/1",
            sourceUrl = "https://cdn.example/master.m3u8",
        )
        assertEquals("BrowserUA", headers["User-Agent"])
    }

    @Test
    fun refererUsesEmbedPageNotMediaFile() {
        val headers = PlaybackRequestHeaders.build(
            fallbackUserAgent = "BrowserUA",
            videoHeaders = emptyMap(),
            pageUrl = "https://embed.example/watch/1",
            sourceUrl = "https://cdn.example/master.m3u8",
        )
        assertEquals("https://embed.example/watch/1", headers["Referer"])
        assertEquals("https://embed.example", headers["Origin"])
    }

    @Test
    fun refererFallsBackToOriginWhenOnlyMediaUrlExists() {
        val referer = PlaybackRequestHeaders.refererFor(
            pageUrl = "https://cdn.example/video/master.m3u8?token=1",
            sourceUrl = "https://cdn.example/video/master.m3u8?token=1",
        )
        assertEquals("https://cdn.example/", referer)
        assertFalse(referer!!.contains("master.m3u8"))
    }

    @Test
    fun keepsExplicitReferer() {
        val headers = PlaybackRequestHeaders.build(
            fallbackUserAgent = "BrowserUA",
            videoHeaders = mapOf("Referer" to "https://site.example/player"),
            pageUrl = "https://other.example/",
            sourceUrl = "https://cdn.example/a.mp4",
        )
        assertEquals("https://site.example/player", headers["Referer"])
        assertTrue(headers.keys.none { it.equals("Origin", ignoreCase = true) && headers[it] == "https://cdn.example" })
    }
}
