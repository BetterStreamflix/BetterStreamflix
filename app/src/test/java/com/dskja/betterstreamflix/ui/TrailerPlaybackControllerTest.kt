package com.dskja.betterstreamflix.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailerPlaybackControllerTest {

    @Test
    fun resolvePreferredPlayer_nonLeanback_nullStored_returnsInApp() {
        val result = TrailerPlaybackController.resolvePreferredPlayer(
            stored = null,
            leanback = false,
            smartTubeStableInstalled = false,
            smartTubeAnyInstalled = false,
            youtubeInstalled = false,
        )
        assertEquals(TrailerPlaybackController.PLAYER_IN_APP, result)
    }

    @Test
    fun resolvePreferredPlayer_keepsExplicitNonInAppChoice() {
        val result = TrailerPlaybackController.resolvePreferredPlayer(
            stored = TrailerPlaybackController.PLAYER_YOUTUBE,
            leanback = true,
            smartTubeStableInstalled = true,
            smartTubeAnyInstalled = true,
            youtubeInstalled = true,
        )
        assertEquals(TrailerPlaybackController.PLAYER_YOUTUBE, result)
    }

    @Test
    fun resolvePreferredPlayer_leanbackUnset_prefersSmartTubeStable() {
        val result = TrailerPlaybackController.resolvePreferredPlayer(
            stored = null,
            leanback = true,
            smartTubeStableInstalled = true,
            smartTubeAnyInstalled = true,
            youtubeInstalled = true,
        )
        assertEquals(TrailerPlaybackController.PLAYER_SMARTTUBE_STABLE, result)
    }

    @Test
    fun playerConstantsAreStable() {
        assertEquals("in_app", TrailerPlaybackController.PLAYER_IN_APP)
        assertEquals("ask", TrailerPlaybackController.PLAYER_ASK)
        assertEquals("youtube", TrailerPlaybackController.PLAYER_YOUTUBE)
        assertEquals("smarttube_stable", TrailerPlaybackController.PLAYER_SMARTTUBE_STABLE)
    }

    @Test
    fun youtubeVideoId_parsesWatchUrl() {
        assertEquals(
            "dQw4w9WgXcQ",
            TrailerPlaybackController.youtubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
        )
    }

    @Test
    fun youtubeVideoId_returnsNullForUnknown() {
        assertNull(TrailerPlaybackController.youtubeVideoId("https://example.com/video"))
        assertNull(TrailerPlaybackController.youtubeVideoId(""))
    }

    @Test
    fun silenceAllActive_invokesRegisteredSessions() {
        var silenced = 0
        val session = TrailerPlaybackController.ActiveSession { silenced += 1 }
        TrailerPlaybackController.registerActiveSession(session)
        try {
            TrailerPlaybackController.silenceAllActive()
            assertEquals(1, silenced)
            TrailerPlaybackController.silenceAllActive()
            assertEquals(2, silenced)
        } finally {
            TrailerPlaybackController.unregisterActiveSession(session)
        }
    }

    @Test
    fun resolvePreferredPlayer_leanbackStoredInApp_overridesToSmartTube() {
        val result = TrailerPlaybackController.resolvePreferredPlayer(
            stored = TrailerPlaybackController.PLAYER_IN_APP,
            leanback = true,
            smartTubeStableInstalled = true,
            smartTubeAnyInstalled = true,
            youtubeInstalled = true,
        )
        assertEquals(TrailerPlaybackController.PLAYER_SMARTTUBE_STABLE, result)
    }

    @Test
    fun resolvePreferredPlayer_leanbackNoSmartTube_usesYoutube() {
        val result = TrailerPlaybackController.resolvePreferredPlayer(
            stored = null,
            leanback = true,
            smartTubeStableInstalled = false,
            smartTubeAnyInstalled = false,
            youtubeInstalled = true,
        )
        assertEquals(TrailerPlaybackController.PLAYER_YOUTUBE, result)
    }

    @Test
    fun youtubeVideoId_parsesShortAndEmbed() {
        assertEquals(
            "dQw4w9WgXcQ",
            TrailerPlaybackController.youtubeVideoId("https://youtu.be/dQw4w9WgXcQ"),
        )
        assertEquals(
            "dQw4w9WgXcQ",
            TrailerPlaybackController.youtubeVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ"),
        )
    }

    @Test
    fun youtubeVideoId_parsesShorts() {
        assertEquals(
            "dQw4w9WgXcQ",
            TrailerPlaybackController.youtubeVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"),
        )
    }

    @Test
    fun youtubeVideoId_nullForVimeo() {
        assertNull(TrailerPlaybackController.youtubeVideoId("https://vimeo.com/123456"))
        assertTrue(TrailerPlaybackController.isVimeoUrl("https://vimeo.com/123456"))
    }

    @Test
    fun resolvePreferredPlayer_leanbackAskWhenNothingInstalled() {
        val result = TrailerPlaybackController.resolvePreferredPlayer(
            stored = null,
            leanback = true,
            smartTubeStableInstalled = false,
            smartTubeAnyInstalled = false,
            youtubeInstalled = false,
        )
        assertEquals(TrailerPlaybackController.PLAYER_ASK, result)
    }

    @Test
    fun resolvePreferredPlayer_leanbackAnySmartTubeWithoutStable() {
        val result = TrailerPlaybackController.resolvePreferredPlayer(
            stored = null,
            leanback = true,
            smartTubeStableInstalled = false,
            smartTubeAnyInstalled = true,
            youtubeInstalled = true,
        )
        assertEquals(TrailerPlaybackController.PLAYER_SMARTTUBE, result)
    }

    @Test
    fun youtubePackagePreferenceOrder_leanbackPrefersTvPackages() {
        val order = TrailerPlaybackController.youtubePackagePreferenceOrder(leanback = true)
        assertEquals(TrailerPlaybackController.YOUTUBE_TV_PACKAGE, order.first())
        assertTrue(order.contains(TrailerPlaybackController.YOUTUBE_TV_PACKAGE_ALT))
    }

    @Test
    fun youtubePackagePreferenceOrder_mobilePrefersPhoneYoutube() {
        val order = TrailerPlaybackController.youtubePackagePreferenceOrder(leanback = false)
        assertEquals(TrailerPlaybackController.YOUTUBE_PACKAGE, order.first())
    }
}
