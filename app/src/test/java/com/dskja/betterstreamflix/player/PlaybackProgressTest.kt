package com.dskja.betterstreamflix.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressTest {

    @Test
    fun unknownDurationDoesNotCountAsFinishedOrStartedAtZero() {
        val unset = PlaybackProgress.TIME_UNSET
        assertFalse(PlaybackProgress.hasFinished(0L, unset))
        assertFalse(PlaybackProgress.hasFinished(5_000L, unset))
        assertFalse(PlaybackProgress.hasStarted(0L, unset))
        assertFalse(PlaybackProgress.hasReallyFinished(0L, unset, 3L))
    }

    @Test
    fun longPositionWithoutDurationCountsAsStartedOnly() {
        assertTrue(PlaybackProgress.hasStarted(25_000L, PlaybackProgress.TIME_UNSET))
        assertFalse(PlaybackProgress.hasFinished(25_000L, PlaybackProgress.TIME_UNSET))
    }

    @Test
    fun shortWindowDoesNotCountAsFinished() {
        assertFalse(PlaybackProgress.hasFinished(28_000L, 30_000L))
        assertFalse(PlaybackProgress.hasReallyFinished(29_000L, 30_000L, 30L))
    }

    @Test
    fun knownDurationUsesPercentThresholds() {
        val duration = 100_000L
        assertFalse(PlaybackProgress.hasStarted(400L, duration))
        assertTrue(PlaybackProgress.hasStarted(600L, duration))
        assertFalse(PlaybackProgress.hasFinished(90_000L, duration))
        assertTrue(PlaybackProgress.hasFinished(90_001L, duration))
        assertTrue(PlaybackProgress.hasReallyFinished(97_000L, duration, 3L))
        assertFalse(PlaybackProgress.hasReallyFinished(90_000L, duration, 3L))
    }

    @Test
    fun resumeRewindNeverGoesNegativeOrPastTheEnd() {
        assertEquals(0L, PlaybackProgress.resumeTargetMs(4_000L))
        assertEquals(5_000L, PlaybackProgress.resumeTargetMs(15_000L))
        assertEquals(80_000L, PlaybackProgress.resumeTargetMs(200_000L, durationMs = 90_000L))
        assertEquals(0L, PlaybackProgress.resumeTargetMs(50_000L, durationMs = 8_000L))
    }

    @Test
    fun lateResumeDoesNotClobberAManualSeek() {
        assertTrue(PlaybackProgress.shouldApplyResume(0L))
        assertTrue(PlaybackProgress.shouldApplyResume(1_200L))
        assertFalse(PlaybackProgress.shouldApplyResume(4_000L))
    }

    @Test
    fun unsetDurationIsNotStoredOverARealOne() {
        assertEquals(90_000L, PlaybackProgress.durationToStore(90_000L, 10_000L))
        assertEquals(10_000L, PlaybackProgress.durationToStore(PlaybackProgress.TIME_UNSET, 10_000L))
        assertEquals(0L, PlaybackProgress.durationToStore(0L, null))
    }
}
