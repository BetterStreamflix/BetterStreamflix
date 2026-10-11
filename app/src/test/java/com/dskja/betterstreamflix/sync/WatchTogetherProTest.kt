package com.dskja.betterstreamflix.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchTogetherProTest {

    @Test
    fun displayModes() {
        assertTrue(WatchTogetherPro.isValidDisplayMode(WatchTogetherPro.MODE_SYNC_ALL))
        assertTrue(WatchTogetherPro.isValidDisplayMode(WatchTogetherPro.MODE_TV_SCREEN))
        assertFalse(WatchTogetherPro.isValidDisplayMode("cast"))
        assertTrue(WatchTogetherPro.isTvScreenMode("tv_screen"))
        assertEquals(WatchTogetherPro.MODE_SYNC_ALL, WatchTogetherPro.normalizeDisplayMode(null))
    }

    @Test
    fun sidekickGates() {
        assertTrue(WatchTogetherPro.shouldApplySeek(2L, 1L, 1_000L))
        assertFalse(WatchTogetherPro.shouldApplySeek(1L, 1L, 1_000L))
        assertTrue(WatchTogetherPro.shouldApplyPlay(3L, 2L, true))
        assertFalse(WatchTogetherPro.shouldApplyPlay(2L, 2L, false))
        assertTrue(WatchTogetherPro.shouldForceResync(5L, 4L))
        assertFalse(WatchTogetherPro.shouldForceResync(4L, 4L))
    }

    @Test
    fun seekHelpersDelegateToSidekick() {
        assertEquals(20_000L, WatchTogetherPro.relativeSeek(10_000L, 10_000L))
        assertEquals(90_000L, WatchTogetherPro.parsePositionInput("1:30"))
        assertEquals("1:30", WatchTogetherPro.formatPosition(90_000L))
    }
}
