package com.dskja.betterstreamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStreamHtmlExtractorTest {

    @Test
    fun extractsConstSrcM3u8() {
        val html = """
            <script>
            const SRC = "https://edge.cowedd4855ws.sbs/premium51/index.m3u8";
            player.setup({ file: SRC });
            </script>
        """.trimIndent()
        assertEquals(
            "https://edge.cowedd4855ws.sbs/premium51/index.m3u8",
            LiveStreamHtmlExtractor.extractM3u8(html),
        )
    }

    @Test
    fun extractsProtocolRelativeM3u8() {
        val html = """source: "//cdn.example.net/live/index.m3u8";"""
        assertEquals(
            "https://cdn.example.net/live/index.m3u8",
            LiveStreamHtmlExtractor.extractM3u8(html),
        )
    }

    @Test
    fun prefersDaddyPremiumEmbedOverAds() {
        val html = """
            <iframe src="https://exmxbxe.cfd/nhmzkzez-6490"></iframe>
            <iframe src="https://dembed.top/premiumtv/daddy.php?id=51" width="100%" id="thatframe"></iframe>
        """.trimIndent()
        assertEquals(
            "https://dembed.top/premiumtv/daddy.php?id=51",
            LiveStreamHtmlExtractor.extractEmbedUrl(html),
        )
    }

    @Test
    fun normalizesDeadDaddy3PlayerPath() {
        val fixed = LiveStreamHtmlExtractor.normalizeDaddyLiveEmbed(
            "https://daddyliveplayer.st/premiumtv/daddy3.php?id=51",
        )
        // Retired host and daddy3.php must not be tried. dembed is the first live mirror.
        assertEquals(
            "https://dembed.top/premiumtv/daddy.php?id=51",
            fixed.first(),
        )
        assertTrue(fixed.none { it.contains("daddy3.php") })
        assertTrue(fixed.none { it.contains("daddyliveplayer.st") })
        assertTrue(fixed.any { it.contains("dembed.top") })
        assertTrue(fixed.any { it.contains("dlive.sx") })
        assertEquals(
            listOf("https://example.com/other"),
            LiveStreamHtmlExtractor.normalizeDaddyLiveEmbed("https://example.com/other"),
        )
        assertNull(LiveStreamHtmlExtractor.extractM3u8("<html>no stream</html>"))
    }
}
