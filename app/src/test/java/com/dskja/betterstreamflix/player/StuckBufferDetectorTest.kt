package com.dskja.betterstreamflix.player

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StuckBufferDetectorTest {

    @Test
    fun offersAfterInitialThreshold() {
        var now = 0L
        val detector = StuckBufferDetector(
            initialThresholdMs = 1_000L,
            rebufferThresholdMs = 800L,
            nowMs = { now },
        )
        detector.onPlaybackStateChanged(Player.STATE_BUFFERING, playWhenReady = true)
        assertFalse(detector.pollShouldOffer(serverCount = 3))
        now = 999L
        assertFalse(detector.pollShouldOffer(serverCount = 3))
        now = 1_000L
        assertTrue(detector.pollShouldOffer(serverCount = 3))
    }

    @Test
    fun usesShorterThresholdAfterPlaybackStarted() {
        var now = 0L
        val detector = StuckBufferDetector(
            initialThresholdMs = 5_000L,
            rebufferThresholdMs = 1_000L,
            nowMs = { now },
        )
        detector.markPlaybackStarted()
        detector.onPlaybackStateChanged(Player.STATE_BUFFERING, playWhenReady = true)
        now = 999L
        assertFalse(detector.pollShouldOffer(serverCount = 2))
        now = 1_000L
        assertTrue(detector.pollShouldOffer(serverCount = 2))
    }

    @Test
    fun clearsWhenReadyOrPlaying() {
        var now = 0L
        val detector = StuckBufferDetector(
            initialThresholdMs = 100L,
            rebufferThresholdMs = 100L,
            nowMs = { now },
        )
        detector.onPlaybackStateChanged(Player.STATE_BUFFERING, playWhenReady = true)
        now = 200L
        assertTrue(detector.pollShouldOffer(serverCount = 2))
        detector.onPlaybackStateChanged(Player.STATE_READY, playWhenReady = true)
        assertFalse(detector.pollShouldOffer(serverCount = 2))

        detector.onPlaybackStateChanged(Player.STATE_BUFFERING, playWhenReady = true)
        now = 500L
        detector.onIsPlayingChanged(true)
        assertFalse(detector.pollShouldOffer(serverCount = 2))
    }

    @Test
    fun ignoresSingleServerLiveDisabledAndResolve() {
        var now = 0L
        val detector = StuckBufferDetector(
            initialThresholdMs = 100L,
            rebufferThresholdMs = 100L,
            nowMs = { now },
        )
        detector.onPlaybackStateChanged(Player.STATE_BUFFERING, playWhenReady = true)
        now = 200L
        assertFalse(detector.pollShouldOffer(serverCount = 1))

        detector.setEnabled(false)
        assertFalse(detector.pollShouldOffer(serverCount = 3))
        detector.setEnabled(true)
        detector.onPlaybackStateChanged(Player.STATE_BUFFERING, playWhenReady = true)
        now = 500L
        detector.setResolveInFlight(true)
        assertFalse(detector.pollShouldOffer(serverCount = 3))
    }

    @Test
    fun offersOnlyOncePerStallUntilCleared() {
        var now = 0L
        val detector = StuckBufferDetector(
            initialThresholdMs = 100L,
            rebufferThresholdMs = 100L,
            nowMs = { now },
        )
        detector.onPlaybackStateChanged(Player.STATE_BUFFERING, playWhenReady = true)
        now = 200L
        assertTrue(detector.pollShouldOffer(serverCount = 3))
        detector.markOffered()
        detector.setOverlayVisible(true)
        assertFalse(detector.pollShouldOffer(serverCount = 3))
        detector.dismissOverlay()
        assertFalse(detector.pollShouldOffer(serverCount = 3))

        detector.onPlaybackStateChanged(Player.STATE_READY, playWhenReady = true)
        detector.onPlaybackStateChanged(Player.STATE_BUFFERING, playWhenReady = true)
        now = 500L
        assertTrue(detector.pollShouldOffer(serverCount = 3))
    }

    @Test
    fun serverChangeResetsClock() {
        var now = 0L
        val detector = StuckBufferDetector(
            initialThresholdMs = 100L,
            rebufferThresholdMs = 100L,
            nowMs = { now },
        )
        detector.onPlaybackStateChanged(Player.STATE_BUFFERING, playWhenReady = true)
        now = 90L
        detector.onServerChanged()
        detector.onPlaybackStateChanged(Player.STATE_BUFFERING, playWhenReady = true)
        assertFalse(detector.pollShouldOffer(serverCount = 3))
        now = 200L
        assertTrue(detector.pollShouldOffer(serverCount = 3))
    }
}
