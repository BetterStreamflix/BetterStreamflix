package com.dskja.betterstreamflix.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastHandoffLinksTest {

    @Test
    fun buildAndParseRoundTrip() {
        val code = "XK9M2P"
        val uri = CastHandoffLinks.buildUri(code)
        assertTrue(uri.startsWith("https://betterstreamflix.app/casthand/"))
        assertEquals(code, CastHandoffLinks.parseShortCode(uri))
    }

    @Test
    fun parseCustomSchemeAndHttps() {
        val code = "ABCOI2"
        assertEquals(code, CastHandoffLinks.parseShortCode("betterstreamflix://casthand?code=$code"))
        assertEquals(code, CastHandoffLinks.parseShortCode(CastHandoffLinks.buildCustomSchemeUri(code)))
        assertEquals(code, CastHandoffLinks.parseShortCode("https://betterstreamflix.app/casthand/$code"))
        assertEquals(
            code,
            CastHandoffLinks.parseShortCode("https://www.betterstreamflix.app/casthand?code=$code"),
        )
    }

    @Test
    fun parseLegacyStreamflixScheme() {
        assertEquals(
            "XK9M2P",
            CastHandoffLinks.parseShortCode("streamflix://casthand/XK9M2P"),
        )
    }

    @Test
    fun rejectsPartyAndCastLinks() {
        assertNull(CastHandoffLinks.parseShortCode("betterstreamflix://party/abcdef12abcdef12"))
        assertNull(CastHandoffLinks.parseShortCode("betterstreamflix://cast/abcdef12abcdef12"))
        assertNull(CastHandoffLinks.parseShortCode("https://example.com/casthand/XK9M2P"))
    }

    @Test
    fun shortCodeNormalization() {
        assertEquals("ABC234", CastHandoffLinks.normalizeShortCode("abc234"))
        assertEquals("ABCOI2", CastHandoffLinks.normalizeShortCode("abc012"))
        assertNull(CastHandoffLinks.normalizeShortCode("short"))
        assertNull(CastHandoffLinks.normalizeShortCode("HAS!@#"))
        assertTrue(CastHandoffLinks.isValidShortCode("XK9M2P"))
        assertFalse(CastHandoffLinks.isValidShortCode("abc"))
    }

    @Test
    fun newShortCodeShape() {
        val code = CastHandoffLinks.newShortCode()
        assertEquals(6, code.length)
        assertTrue(CastHandoffLinks.isValidShortCode(code))
    }

    @Test
    fun sessionPendingPositionConsumesOnce() {
        CastHandoffSession.clear()
        CastHandoffSession.beginClaim("XK9M2P", "id-1", 125_000L, "Server A")
        assertTrue(CastHandoffSession.hasPendingPosition())
        assertEquals(125_000L, CastHandoffSession.consumePendingPositionMs())
        assertFalse(CastHandoffSession.hasPendingPosition())
        assertNull(CastHandoffSession.consumePendingPositionMs())
        CastHandoffSession.clear()
    }

    @Test
    fun formatPositionLabel() {
        assertEquals("1:05", CastHandoffRepository.formatPositionLabel(65_000L))
        assertEquals("1:02:03", CastHandoffRepository.formatPositionLabel(3_723_000L))
    }

    @Test
    fun toVideoTypeMovieAndEpisode() {
        val movie = CastHandoffRepository.toVideoType(
            CastHandoffPayload(
                provider = "test",
                mediaType = "movie",
                mediaId = "m1",
                title = "Film",
                releaseDate = "2024",
                poster = "p",
            ),
        )
        assertTrue(movie is com.dskja.betterstreamflix.models.Video.Type.Movie)

        val episode = CastHandoffRepository.toVideoType(
            CastHandoffPayload(
                provider = "test",
                mediaType = "episode",
                mediaId = "e1",
                title = "Show",
                showId = "s1",
                showTitle = "Show",
                seasonNumber = 2,
                episodeNumber = 4,
                episodeTitle = "Title",
            ),
        )
        assertTrue(episode is com.dskja.betterstreamflix.models.Video.Type.Episode)
        val ep = episode as com.dskja.betterstreamflix.models.Video.Type.Episode
        assertEquals(2, ep.season.number)
        assertEquals(4, ep.number)
    }
}
