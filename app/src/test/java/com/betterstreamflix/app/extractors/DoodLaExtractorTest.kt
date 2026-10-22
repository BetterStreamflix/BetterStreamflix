package com.betterstreamflix.app.extractors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoodLaExtractorTest {

    @Test
    fun findMd5Path_fromGoldenEmbedHtml() {
        val html = GOLDEN_EMBED_HTML
        assertEquals(
            "/pass_md5/doodstream/abc123tokenpath",
            DoodLaPlayback.findMd5Path(html),
        )
    }

    @Test
    fun findPageToken_fromGoldenEmbedHtml() {
        assertEquals(
            "pageTokenXYZ",
            DoodLaPlayback.findPageToken(GOLDEN_EMBED_HTML),
        )
    }

    @Test
    fun findMd5Path_returnsNullWhenMissing() {
        assertNull(DoodLaPlayback.findMd5Path("<html><body>no pass</body></html>"))
    }

    @Test
    fun buildPlaybackUrl_includesTokenAndExpiry() {
        val url = DoodLaPlayback.buildPlaybackUrl(
            videoPrefix = "https://cdn.example/video/",
            hash = "Ab12Cd34Ef",
            token = "tok123",
            expiryMs = 1_700_000_000_000L,
        )
        assertEquals(
            "https://cdn.example/video/Ab12Cd34Ef?token=tok123&expiry=1700000000000",
            url,
        )
    }

    @Test
    fun buildPlaybackHeaders_includeCookieWhenPresent() {
        val headers = DoodLaPlayback.buildPlaybackHeaders(
            finalUrl = "https://playmogo.com/e/abc",
            finalBaseUrl = "https://playmogo.com",
            playbackUserAgent = "TestUA",
            cookieHeader = "lang=1; session=xyz",
        )
        assertEquals("https://playmogo.com/e/abc", headers["Referer"])
        assertEquals("https://playmogo.com", headers["Origin"])
        assertEquals("TestUA", headers["User-Agent"])
        assertEquals("*/*", headers["Accept"])
        assertEquals("lang=1; session=xyz", headers["Cookie"])
    }

    @Test
    fun buildPlaybackHeaders_omitBlankCookie() {
        val headers = DoodLaPlayback.buildPlaybackHeaders(
            finalUrl = "https://dood.la/e/abc",
            finalBaseUrl = "https://dood.la",
            playbackUserAgent = "TestUA",
            cookieHeader = "   ",
        )
        assertTrue(headers.keys.none { it.equals("Cookie", ignoreCase = true) })
    }

    companion object {
        // Minimal golden fragment matching pass_md5 + token patterns from Dood/Playmogo embeds.
        private const val GOLDEN_EMBED_HTML = """
            <html><script>
            get_md5 = "/pass_md5/doodstream/abc123tokenpath";
            makePlay = function(){ return "/pass_md5/doodstream/abc123tokenpath?token=pageTokenXYZ"; };
            </script></html>
        """
    }
}
