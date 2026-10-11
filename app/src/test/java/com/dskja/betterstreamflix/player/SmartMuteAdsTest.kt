package com.dskja.betterstreamflix.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SmartMuteAdsTest {

    private lateinit var watchdog: SmartMuteAds.Watchdog

    @Before
    fun setUp() {
        watchdog = SmartMuteAds.Watchdog()
    }

    private fun snap(
        now: Long,
        position: Long,
        buffered: Long = position + 5_000L,
        duration: Long = 45L * 60_000L,
        phase: SmartMuteAds.PlaybackPhase = SmartMuteAds.PlaybackPhase.BUFFERING,
        isPlaying: Boolean = false,
        playWhenReady: Boolean = true,
        isPlayingAd: Boolean = false,
        isLive: Boolean = false,
        isOffline: Boolean = false,
    ) = SmartMuteAds.Snapshot(
        nowMs = now,
        positionMs = position,
        bufferedPositionMs = buffered,
        durationMs = duration,
        phase = phase,
        isPlaying = isPlaying,
        playWhenReady = playWhenReady,
        isPlayingAd = isPlayingAd,
        isLive = isLive,
        isOffline = isOffline,
    )

    @Test
    fun doesNotSeekWhilePositionAdvances() {
        assertNull(watchdog.onTick(snap(0, 1_000)))
        assertNull(watchdog.onTick(snap(1_000, 2_000, phase = SmartMuteAds.PlaybackPhase.READY, isPlaying = true)))
        assertNull(watchdog.onTick(snap(2_000, 3_500, phase = SmartMuteAds.PlaybackPhase.READY, isPlaying = true)))
    }

    @Test
    fun seeksStuckPrerollAfterFreeze() {
        assertNull(watchdog.onTick(snap(0, 5_000)))
        // Frozen at 5s for 8s while buffering in pre-roll window.
        assertNull(watchdog.onTick(snap(4_000, 5_000)))
        val action = watchdog.onTick(snap(8_500, 5_000))
        assertTrue(action != null)
        assertEquals(SmartMuteAds.Reason.STUCK_PREROLL, action!!.reason)
        assertTrue(action.positionMs > 5_000L)
        assertTrue(action.positionMs - 5_000L <= SmartMuteAds.MAX_SEEK_JUMP_MS)
    }

    @Test
    fun seeksStuckExplicitAd() {
        assertNull(watchdog.onTick(snap(0, 120_000, isPlayingAd = true)))
        assertNull(watchdog.onTick(snap(4_000, 120_000, isPlayingAd = true)))
        val action = watchdog.onTick(snap(9_000, 120_000, isPlayingAd = true))
        assertEquals(SmartMuteAds.Reason.STUCK_AD, action!!.reason)
    }

    @Test
    fun neverSeeksLiveOrOffline() {
        assertNull(watchdog.onTick(snap(0, 1_000, isLive = true)))
        assertNull(watchdog.onTick(snap(10_000, 1_000, isLive = true)))
        watchdog.reset()
        assertNull(watchdog.onTick(snap(0, 1_000, isOffline = true)))
        assertNull(watchdog.onTick(snap(10_000, 1_000, isOffline = true)))
    }

    @Test
    fun neverSeeksWhenUserPaused() {
        assertNull(watchdog.onTick(snap(0, 10_000)))
        assertNull(
            watchdog.onTick(
                snap(
                    10_000,
                    10_000,
                    phase = SmartMuteAds.PlaybackPhase.READY,
                    playWhenReady = false,
                    isPlaying = false,
                ),
            ),
        )
    }

    @Test
    fun neverSeeksNearEndCredits() {
        val duration = 60L * 60_000L
        val nearEnd = (duration * 0.95).toLong()
        assertNull(watchdog.onTick(snap(0, nearEnd, duration = duration)))
        assertNull(watchdog.onTick(snap(10_000, nearEnd, duration = duration)))
    }

    @Test
    fun midrollRequiresPriorContentAdvance() {
        // Freeze mid-title without ever leaving pre-roll → no mid-roll rescue.
        assertNull(watchdog.onTick(snap(0, 200_000)))
        assertNull(watchdog.onTick(snap(9_000, 200_000)))
        // Still null: never advanced past pre-roll in this session.
        assertNull(watchdog.onTick(snap(9_000, 200_000)))
    }

    @Test
    fun midrollSeeksAfterContentThenHang() {
        // Advance through pre-roll into content.
        assertNull(
            watchdog.onTick(
                snap(0, 20_000, phase = SmartMuteAds.PlaybackPhase.READY, isPlaying = true),
            ),
        )
        assertNull(
            watchdog.onTick(
                snap(1_000, 100_000, phase = SmartMuteAds.PlaybackPhase.READY, isPlaying = true),
            ),
        )
        // Mid-roll hang while buffering.
        assertNull(watchdog.onTick(snap(2_000, 100_000)))
        val action = watchdog.onTick(snap(11_000, 100_000))
        assertEquals(SmartMuteAds.Reason.STUCK_MIDROLL, action!!.reason)
        assertTrue(action.positionMs > 100_000L)
    }

    @Test
    fun userSeekSuppressesImmediateRescue() {
        assertNull(watchdog.onTick(snap(0, 5_000)))
        watchdog.onUserSeek(1_000)
        assertNull(watchdog.onTick(snap(5_000, 5_000)))
        assertNull(watchdog.onTick(snap(8_000, 5_000)))
    }

    @Test
    fun respectsSessionSeekBudget() {
        val w = SmartMuteAds.Watchdog()
        fun fireSeek(base: Long): SmartMuteAds.SeekTo? {
            w.onTick(snap(base, 3_000))
            w.onTick(snap(base + 4_000, 3_000))
            return w.onTick(snap(base + 9_000, 3_000))
        }
        // Spacing must exceed COOLDOWN_AFTER_SEEK_MS (12s).
        assertTrue(fireSeek(0) != null)
        assertTrue(fireSeek(30_000) != null)
        assertTrue(fireSeek(60_000) != null)
        assertNull(fireSeek(90_000))
    }

    @Test
    fun prefersBufferedLandingWithinJumpCap() {
        assertNull(watchdog.onTick(snap(0, 2_000, buffered = 2_000 + 25_000)))
        assertNull(watchdog.onTick(snap(4_000, 2_000, buffered = 2_000 + 25_000)))
        val action = watchdog.onTick(snap(9_000, 2_000, buffered = 2_000 + 25_000))
        assertEquals(2_000L + 25_000L, action!!.positionMs)
    }

    @Test
    fun phaseFromMedia3MapsBuffering() {
        assertEquals(SmartMuteAds.PlaybackPhase.BUFFERING, SmartMuteAds.phaseFromMedia3(2))
        assertEquals(SmartMuteAds.PlaybackPhase.READY, SmartMuteAds.phaseFromMedia3(3))
    }
}
