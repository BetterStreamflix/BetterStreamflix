package com.dskja.betterstreamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uPlaylistParserTest {

    @Test
    fun parsesBareExtinfWithoutHash() {
        val raw = """
            #EXTM3U
            EXTINF:-1 tvg-logo="https://logo/a.png" group-title="News",Channel A
            https://cdn.example.net/a.m3u8
            #EXTINF:-1 tvg-logo="https://logo/b.png" group-title="Sports",Channel B
            https://cdn.example.net/b.m3u8
        """.trimIndent()

        val channels = M3uPlaylistParser.parse(raw)
        assertEquals(2, channels.size)
        assertEquals("Channel A", channels[0].name)
        assertEquals("https://cdn.example.net/a.m3u8", channels[0].url)
        assertEquals("News", channels[0].group)
        assertEquals("Channel B", channels[1].name)
    }

    @Test
    fun usesTvgNameWhenCommaLabelIsBlank() {
        val raw = """
            #EXTM3U
            #EXTINF:-1 tvg-name="BBC One" tvg-logo="https://logo/bbc.png" group-title="UK",
            https://edge.example.net/bbc/index.m3u8
        """.trimIndent()
        val channels = M3uPlaylistParser.parse(raw)
        assertEquals(1, channels.size)
        assertEquals("BBC One", channels[0].name)
        assertEquals("UK", channels[0].group)
    }

    @Test
    fun skipsDeadPlaceholderHosts() {
        val raw = """
            #EXTM3U
            #EXTINF:-1,Dead
            https://sinurl.com
            EXTINF:-1,Live
            https://edge.example.net/live/index.m3u8
        """.trimIndent()

        val channels = M3uPlaylistParser.parse(raw)
        assertEquals(1, channels.size)
        assertEquals("Live", channels[0].name)
        assertFalse(M3uPlaylistParser.isPlayableUrl("https://sinurl.com/x"))
        assertTrue(M3uPlaylistParser.isPlayableUrl("https://edge.example.net/live/index.m3u8"))
    }

    @Test
    fun usesTvgNameWhenExtinfHasNoComma() {
        val raw = """
            #EXTM3U
            #EXTINF:-1 tvg-name="Sky Sports" group-title="Sports"
            https://edge.example.net/sky/index.m3u8
        """.trimIndent()
        val channels = M3uPlaylistParser.parse(raw)
        assertEquals(1, channels.size)
        assertEquals("Sky Sports", channels[0].name)
        assertEquals("Sports", channels[0].group)
    }

    @Test
    fun splitsPipeHeadersOffThePlayableUrl() {
        val raw = """
            #EXTM3U
            #EXTINF:-1,Piped
            http://edge.example.net/live.m3u8|User-Agent=TestAgent|Referer=https://pluto.tv/
        """.trimIndent()
        val channels = M3uPlaylistParser.parse(raw)
        assertEquals(1, channels.size)
        assertEquals("http://edge.example.net/live.m3u8", channels[0].url)
        assertEquals("TestAgent", channels[0].userAgent)
        assertEquals("https://pluto.tv/", channels[0].referrer)
    }

    @Test
    fun keepsExtvlcoptHeaders() {
        val raw = """
            #EXTM3U
            #EXTINF:-1,With UA
            #EXTVLCOPT:http-user-agent=Android 8.0.0
            #EXTVLCOPT:http-referrer=https://pluto.tv/
            https://jmp2.uk/plu-abc.m3u8
        """.trimIndent()

        val channels = M3uPlaylistParser.parse(raw)
        assertEquals(1, channels.size)
        assertEquals("Android 8.0.0", channels[0].userAgent)
        assertEquals("https://pluto.tv/", channels[0].referrer)
    }
}
