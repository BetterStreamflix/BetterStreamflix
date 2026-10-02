package com.dskja.betterstreamflix.platform.playerbackend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
