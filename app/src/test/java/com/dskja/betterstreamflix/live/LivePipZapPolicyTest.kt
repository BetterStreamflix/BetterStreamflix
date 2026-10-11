package com.dskja.betterstreamflix.live

import com.dskja.betterstreamflix.models.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LivePipZapPolicyTest {

    @Before
    fun setUp() {
        LivePipZapSession.clear()
    }

    @Test
    fun eligibleOnlyForVodWithCandidate() {
        val base = LivePipZapPolicy.Eligibility(
            isLivePlayback = false,
            isOffline = false,
            isKidsOrAgeGated = false,
            isCasting = false,
            isSystemPip = false,
            hasChannelCandidate = true,
        )
        assertTrue(LivePipZapPolicy.isEligible(base))
        assertFalse(LivePipZapPolicy.isEligible(base.copy(isLivePlayback = true)))
        assertFalse(LivePipZapPolicy.isEligible(base.copy(isOffline = true)))
        assertFalse(LivePipZapPolicy.isEligible(base.copy(isKidsOrAgeGated = true)))
        assertFalse(LivePipZapPolicy.isEligible(base.copy(isCasting = true)))
        assertFalse(LivePipZapPolicy.isEligible(base.copy(isSystemPip = true)))
        assertFalse(LivePipZapPolicy.isEligible(base.copy(hasChannelCandidate = false)))
    }

    @Test
    fun previewIsAlwaysMuted() {
        assertTrue(LivePipZapPolicy.previewShouldMute())
        assertTrue(LivePipZapPolicy.PREVIEW_MUTED)
    }

    @Test
    fun pickChannelPrefersPinsThenFavorites() {
        val pins = listOf(
            LivePipZapPolicy.Candidate("1", "DaddyLive TV", "Pin One"),
            LivePipZapPolicy.Candidate("2", "DaddyLive TV", "Pin Two"),
        )
        val favorites = listOf(
            LivePipZapPolicy.Candidate("9", "Pluto TV", "Fav Nine"),
        )
        assertEquals("1", LivePipZapPolicy.pickChannel(pins, favorites)?.id)
        assertEquals("9", LivePipZapPolicy.pickChannel(emptyList(), favorites)?.id)
        assertNull(LivePipZapPolicy.pickChannel(emptyList(), emptyList()))
    }

    @Test
    fun pickChannelUsesRecentOnlyWhenKnown() {
        val pins = listOf(LivePipZapPolicy.Candidate("1", "DaddyLive TV", "Pin"))
        val favorites = listOf(LivePipZapPolicy.Candidate("9", "Pluto TV", "Fav"))
        val knownRecent = LivePipZapPolicy.Candidate("9", "Pluto TV", "Fav")
        val unknownRecent = LivePipZapPolicy.Candidate("77", "Other", "Ghost")
        // Pins still win over recent.
        assertEquals("1", LivePipZapPolicy.pickChannel(pins, favorites, knownRecent)?.id)
        // Without pins, known recent wins over later favorites order… favorites firstOrNull
        // when pins empty: recent known returns recent before favorites scan.
        assertEquals(
            "9",
            LivePipZapPolicy.pickChannel(emptyList(), favorites, knownRecent)?.id,
        )
        assertEquals(
            "9",
            LivePipZapPolicy.pickChannel(emptyList(), favorites, unknownRecent)?.id,
        )
    }

    @Test
    fun mergeCandidatesDedupesByKeyPinsFirst() {
        val pins = listOf(LivePipZapPolicy.Candidate("1", "A", "Pin"))
        val favorites = listOf(
            LivePipZapPolicy.Candidate("1", "A", "Fav Same"),
            LivePipZapPolicy.Candidate("2", "B", "Fav Two"),
        )
        val merged = LivePipZapPolicy.mergeCandidates(pins, favorites)
        assertEquals(2, merged.size)
        assertEquals("Pin", merged.first().title)
        assertEquals("2", merged[1].id)
    }

    @Test
    fun tvOverlayFocusableOnlyWhenChromeVisible() {
        assertTrue(LivePipZapPolicy.tvOverlayFocusable(chromeVisible = true))
        assertFalse(LivePipZapPolicy.tvOverlayFocusable(chromeVisible = false))
    }

    @Test
    fun buildReturnTicketRequiresProviderAndId() {
        val movie = Video.Type.Movie(
            id = "m1",
            title = "Film",
            releaseDate = "2024",
            poster = "",
            imdbId = null,
        )
        assertNull(
            LivePipZapPolicy.buildReturnTicket(
                providerName = null,
                id = "m1",
                videoType = movie,
                title = "Film",
                subtitle = "",
                preferredServerName = null,
                positionMs = 12_000L,
            ),
        )
        val ticket = LivePipZapPolicy.buildReturnTicket(
            providerName = "SerienStream",
            id = "m1",
            videoType = movie,
            title = "Film",
            subtitle = "",
            preferredServerName = "Server A",
            positionMs = -5L,
        )
        assertNotNull(ticket)
        assertEquals(0L, ticket!!.positionMs)
        assertEquals("SerienStream", ticket.providerName)
    }

    @Test
    fun sessionStashPeekAndReturnConsume() {
        val movie = Video.Type.Movie(
            id = "m1",
            title = "Film",
            releaseDate = "2024",
            poster = "",
            imdbId = null,
        )
        val ticket = LivePipZapPolicy.buildReturnTicket(
            providerName = "SerienStream",
            id = "m1",
            videoType = movie,
            title = "Film",
            subtitle = "",
            preferredServerName = null,
            positionMs = 1000L,
        )!!
        LivePipZapSession.stash(ticket)
        assertTrue(LivePipZapSession.hasReturnTicket())
        assertEquals("m1", LivePipZapSession.peek()?.id)
        val consumed = LivePipZapSession.beginReturn()
        assertEquals("m1", consumed?.id)
        assertFalse(LivePipZapSession.hasReturnTicket())
        // Destroy during return must not resurrect a clear.
        LivePipZapSession.onPlayerDestroyed(wasLivePlayback = true)
        assertFalse(LivePipZapSession.hasReturnTicket())
    }

    @Test
    fun orphanLiveDestroyClearsTicket() {
        val movie = Video.Type.Movie(
            id = "m1",
            title = "Film",
            releaseDate = "2024",
            poster = "",
            imdbId = null,
        )
        LivePipZapSession.stash(
            LivePipZapPolicy.buildReturnTicket(
                providerName = "SerienStream",
                id = "m1",
                videoType = movie,
                title = "Film",
                subtitle = "",
                preferredServerName = null,
                positionMs = 0L,
            )!!,
        )
        // Promote nav consume-protect, then leaving live for real.
        LivePipZapSession.onPlayerDestroyed(wasLivePlayback = true)
        assertTrue(LivePipZapSession.hasReturnTicket())
        LivePipZapSession.onPlayerDestroyed(wasLivePlayback = true)
        assertFalse(LivePipZapSession.hasReturnTicket())
    }

    @Test
    fun vodDestroyDoesNotClearTicket() {
        val movie = Video.Type.Movie(
            id = "m1",
            title = "Film",
            releaseDate = "2024",
            poster = "",
            imdbId = null,
        )
        LivePipZapSession.stash(
            LivePipZapPolicy.buildReturnTicket(
                providerName = "SerienStream",
                id = "m1",
                videoType = movie,
                title = "Film",
                subtitle = "",
                preferredServerName = null,
                positionMs = 0L,
            )!!,
        )
        LivePipZapSession.onPlayerDestroyed(wasLivePlayback = false)
        assertTrue(LivePipZapSession.hasReturnTicket())
    }

    @Test
    fun promoteNavDestroyKeepsTicketEvenIfAlreadyLiveSticky() {
        val movie = Video.Type.Movie(
            id = "m1",
            title = "Film",
            releaseDate = "2024",
            poster = "",
            imdbId = null,
        )
        LivePipZapSession.stash(
            LivePipZapPolicy.buildReturnTicket(
                providerName = "SerienStream",
                id = "m1",
                videoType = movie,
                title = "Film",
                subtitle = "",
                preferredServerName = null,
                positionMs = 0L,
            )!!,
        )
        // Sticky switch already flipped isLivePlayback before VOD onDestroyView.
        LivePipZapSession.onPlayerDestroyed(wasLivePlayback = true)
        assertTrue(LivePipZapSession.hasReturnTicket())
        // Leaving live without return clears it.
        LivePipZapSession.onPlayerDestroyed(wasLivePlayback = true)
        assertFalse(LivePipZapSession.hasReturnTicket())
    }
}
