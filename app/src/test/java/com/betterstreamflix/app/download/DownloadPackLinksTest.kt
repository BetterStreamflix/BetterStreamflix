package com.betterstreamflix.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPackLinksTest {

    private fun samplePayload(episodes: Int = 3): DownloadPackPayload =
        DownloadPackPayload(
            providerName = "SerienStream",
            tvShowId = "show-42",
            tvShowTitle = "Example Show",
            seasonNumber = 2,
            posterUrl = "https://img.example/p.jpg",
            episodes = (1..episodes).map { n ->
                DownloadPackPayload.EpisodeRef(
                    id = "ep-$n",
                    number = n,
                    title = "Episode $n",
                    posterUrl = "",
                )
            },
        )

    @Test
    fun buildAndParseRoundTripHttps() {
        val payload = samplePayload()
        val uri = DownloadPackLinks.buildUri(payload)
        assertTrue(uri.startsWith("https://betterstreamflix.app/pack?d="))
        val parsed = DownloadPackLinks.parse(uri)
        assertNotNull(parsed)
        assertEquals(payload.providerName, parsed!!.providerName)
        assertEquals(payload.tvShowId, parsed.tvShowId)
        assertEquals(payload.tvShowTitle, parsed.tvShowTitle)
        assertEquals(payload.seasonNumber, parsed.seasonNumber)
        assertEquals(payload.episodes.size, parsed.episodes.size)
        assertEquals(payload.episodes.first().id, parsed.episodes.first().id)
        assertEquals(payload.episodes.last().number, parsed.episodes.last().number)
    }

    @Test
    fun parseCustomSchemeAndWww() {
        val payload = samplePayload(1)
        val custom = DownloadPackLinks.buildCustomSchemeUri(payload)
        assertTrue(custom.startsWith("betterstreamflix://pack?d="))
        assertEquals(payload.tvShowId, DownloadPackLinks.parse(custom)!!.tvShowId)

        val encoded = DownloadPackLinks.encode(payload)
        assertEquals(
            payload.tvShowId,
            DownloadPackLinks.parse("https://www.betterstreamflix.app/pack?d=$encoded")!!.tvShowId,
        )
        assertEquals(
            payload.tvShowId,
            DownloadPackLinks.parse("streamflix://pack?d=$encoded")!!.tvShowId,
        )
    }

    @Test
    fun rejectsForeignOrCorruptLinks() {
        assertNull(DownloadPackLinks.parse("betterstreamflix://list/abcdef12abcdef12"))
        assertNull(DownloadPackLinks.parse("https://example.com/pack?d=abc"))
        assertNull(DownloadPackLinks.parse("betterstreamflix://pack?d=!!!"))
        assertNull(DownloadPackLinks.parse("betterstreamflix://pack"))
        assertFalse(DownloadPackLinks.isPackLink("https://betterstreamflix.app/party/token"))
    }

    @Test
    fun encodeRejectsEmptyEpisodes() {
        runCatching {
            DownloadPackLinks.encode(
                DownloadPackPayload(
                    providerName = "SerienStream",
                    tvShowId = "s",
                    tvShowTitle = "T",
                    seasonNumber = 1,
                    episodes = emptyList(),
                ),
            )
        }.fold(
            onSuccess = { throw AssertionError("expected failure") },
            onFailure = { assertTrue(it is IllegalArgumentException) },
        )
    }

    @Test
    fun qrSizedSeasonFitsLimit() {
        val payload = samplePayload(12)
        val encoded = DownloadPackLinks.encode(payload)
        assertTrue(encoded.length <= DownloadPackLinks.MAX_ENCODED_CHARS)
        assertEquals(12, DownloadPackLinks.decode(encoded)!!.episodeCount())
    }

    @Test
    fun encodeRejectsOversizedIdFields() {
        val tooLong = "x".repeat(DownloadPackPayload.MAX_ID_CHARS + 1)
        runCatching {
            DownloadPackLinks.encode(
                DownloadPackPayload(
                    providerName = tooLong,
                    tvShowId = "show",
                    tvShowTitle = "T",
                    seasonNumber = 1,
                    episodes = listOf(DownloadPackPayload.EpisodeRef(id = "ep-1", number = 1)),
                ),
            )
        }.fold(
            onSuccess = { throw AssertionError("expected failure") },
            onFailure = { assertTrue(it is IllegalArgumentException) },
        )
    }

    @Test
    fun decodeRejectsExpiredV2Pack() {
        val payload = samplePayload(1).copy(
            version = DownloadPackPayload.VERSION,
            expiresAtEpochMs = System.currentTimeMillis() - 1_000L,
        )
        val encoded = DownloadPackLinks.encode(payload)
        assertNull(DownloadPackLinks.decode(encoded))
    }

    @Test
    fun encodeIncludesExpiresAtRoundTrip() {
        val payload = samplePayload(2).copy(
            expiresAtEpochMs = System.currentTimeMillis() + DownloadPackPayload.SHARE_TTL_MS,
        )
        val encoded = DownloadPackLinks.encode(payload)
        val parsed = DownloadPackLinks.decode(encoded)
        assertNotNull(parsed)
        assertEquals(DownloadPackPayload.VERSION, parsed!!.version)
        assertTrue(parsed.expiresAtEpochMs > 0L)
        assertEquals(payload.tvShowId, parsed.tvShowId)
    }
}
