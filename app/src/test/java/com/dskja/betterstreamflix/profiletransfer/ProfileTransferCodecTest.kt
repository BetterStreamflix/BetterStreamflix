package com.dskja.betterstreamflix.profiletransfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTransferCodecTest {

    private fun samplePayload(
        watchlistSize: Int = 2,
        withPosters: Boolean = true,
    ): ProfileTransferPayload {
        val items = (1..watchlistSize).map { i ->
            ProfileTransferPayload.WatchlistItem(
                provider = "SerienStream",
                mediaType = if (i % 2 == 0) "movie" else "tv_show",
                mediaId = "/id/$i",
                title = "Title $i",
                poster = if (withPosters) "https://example.com/p$i.jpg" else null,
                banner = if (withPosters) "https://example.com/b$i.jpg" else null,
                favoritedAtMillis = 1_700_000_000_000L + i,
            )
        }
        return ProfileTransferPayload(
            v = ProfileTransferPayload.VERSION,
            kind = ProfileTransferPayload.KIND,
            exportedAt = 1_700_000_000_000L,
            sourceProfileId = "default",
            persona = ProfileTransferPayload.Persona(
                displayName = "Alex",
                avatarKey = "ocean",
                isKids = false,
                enabledIntegrations = listOf("tmdb", "simkl"),
            ),
            settings = ProfileTransferPayload.SettingsSnapshot(
                libraryScope = "cross_provider",
                selectedTheme = "cinema_ink_dark",
                showContinueWatching = true,
                showWatchlistWeek = true,
                homeShelfBlockOrder = "continue,live,collections",
            ),
            watchlist = items,
            notes = listOf(
                ProfileTransferPayload.NoteItem("ep-1", "Paused after kitchen scene"),
            ),
        )
    }

    @Test
    fun fileJsonRoundTrip() {
        val original = samplePayload()
        val json = ProfileTransferCodec.encodeFileJson(original)
        assertTrue(json.contains("bsf.profile"))
        val decoded = ProfileTransferCodec.decodeFileJson(json)
        assertNotNull(decoded)
        assertEquals(original.v, decoded!!.v)
        assertEquals(original.kind, decoded.kind)
        assertEquals(original.persona?.displayName, decoded.persona?.displayName)
        assertEquals(original.settings.libraryScope, decoded.settings.libraryScope)
        assertEquals(original.watchlist.size, decoded.watchlist.size)
        assertEquals(original.notes.first().text, decoded.notes.first().text)
        assertEquals(original.watchlist.first().poster, decoded.watchlist.first().poster)
    }

    @Test
    fun qrRoundTripStripsPosters() {
        val original = samplePayload(watchlistSize = 3, withPosters = true)
        val uri = ProfileTransferLinks.buildUri(original)
        assertTrue(uri.startsWith("https://betterstreamflix.app/profile?d="))
        val decoded = ProfileTransferLinks.parse(uri)
        assertNotNull(decoded)
        assertEquals(3, decoded!!.watchlist.size)
        assertNull(decoded.watchlist.first().poster)
        assertNull(decoded.watchlist.first().banner)
        assertEquals("Title 1", decoded.watchlist.first().title)
        assertEquals("Paused after kitchen scene", decoded.notes.first().text)
        assertEquals("cross_provider", decoded.settings.libraryScope)
    }

    @Test
    fun customSchemeParsed() {
        val uri = ProfileTransferLinks.buildCustomSchemeUri(samplePayload(1))
        assertTrue(uri.startsWith("betterstreamflix://profile?d="))
        assertNotNull(ProfileTransferLinks.parse(uri))
    }

    @Test
    fun rejectBadKindAndVersion() {
        assertNull(ProfileTransferCodec.decodeFileJson("""{"v":1,"kind":"nope"}"""))
        assertNull(ProfileTransferCodec.decodeFileJson("""{"v":99,"kind":"bsf.profile"}"""))
        assertNull(ProfileTransferCodec.decodeFileJson(""))
        assertNull(ProfileTransferCodec.decodeFileJson(null))
    }

    @Test
    fun forQrCapsWatchlist() {
        val big = samplePayload(watchlistSize = 120)
        val qr = big.forQr()
        assertEquals(ProfileTransferPayload.MAX_QR_WATCHLIST, qr.watchlist.size)
        assertTrue(qr.watchlist.all { it.poster == null })
    }

    @Test
    fun decodeFileJsonAcceptsQrUri() {
        val uri = ProfileTransferLinks.buildUri(samplePayload(1))
        val decoded = ProfileTransferCodec.decodeFileJson(uri)
        assertNotNull(decoded)
        assertEquals(1, decoded!!.watchlist.size)
    }

    @Test
    fun isProfileLink() {
        assertTrue(ProfileTransferLinks.isProfileLink(ProfileTransferLinks.buildUri(samplePayload(1))))
        assertFalse(ProfileTransferLinks.isProfileLink("https://betterstreamflix.app/pack?d=abc"))
        assertFalse(ProfileTransferLinks.isProfileLink(null))
    }
}
