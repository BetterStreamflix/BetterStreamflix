package com.betterstreamflix.app.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvWatchDeepLinkTest {

    @Test
    fun buildAndParseRoundTrip() {
        val uri = TvWatchDeepLink.buildUri(
            TvWatchDeepLink.Kind.EPISODE,
            "ep-42",
            "Sflix",
        )
        assertTrue(uri.startsWith("betterstreamflix://watch?"))
        val target = TvWatchDeepLink.parse(uri)!!
        assertEquals(TvWatchDeepLink.Kind.EPISODE, target.kind)
        assertEquals("ep-42", target.id)
        assertEquals("Sflix", target.provider)
    }

    @Test
    fun parsesLegacySchemeAndEncodedProvider() {
        val uri = "streamflix://watch?kind=movie&id=tt123&provider=TMDb%20%28en%29"
        val target = TvWatchDeepLink.parse(uri)!!
        assertEquals(TvWatchDeepLink.Kind.MOVIE, target.kind)
        assertEquals("tt123", target.id)
        assertEquals("TMDb (en)", target.provider)
    }

    @Test
    fun contentIdRoundTrip() {
        val contentId = TvWatchDeepLink.contentId(TvWatchDeepLink.Kind.TV_SHOW, "show-9")
        assertEquals("tvshow:show-9", contentId)
        val parsed = TvWatchDeepLink.parseContentId(contentId)!!
        assertEquals(TvWatchDeepLink.Kind.TV_SHOW, parsed.first)
        assertEquals("show-9", parsed.second)
    }

    @Test
    fun rejectsForeignOrIncompleteLinks() {
        assertNull(TvWatchDeepLink.parse("betterstreamflix://list?token=abc"))
        assertNull(TvWatchDeepLink.parse("betterstreamflix://watch?kind=movie"))
        assertNull(TvWatchDeepLink.parse("https://example.com/watch?kind=movie&id=1"))
        assertNull(TvWatchDeepLink.parseContentId("nocolon"))
    }
}
