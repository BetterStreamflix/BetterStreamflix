package com.dskja.betterstreamflix.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadWatchPolicyTest {
    @Test
    fun unsetDurationDoesNotMarkWatched() {
        val unset = Long.MIN_VALUE
        assertFalse(DownloadWatchPolicy.shouldMarkWatched(5_000L, unset))
        assertFalse(DownloadWatchPolicy.hasStarted(0L, unset))
        assertFalse(DownloadWatchPolicy.hasReallyFinished(5_000L, unset, 30L))
    }

    @Test
    fun shortHlsWindowDoesNotMarkWatched() {
        assertFalse(DownloadWatchPolicy.shouldMarkWatched(28_000L, 30_000L))
        assertFalse(DownloadWatchPolicy.hasReallyFinished(29_000L, 30_000L, 10L))
    }

    @Test
    fun nearEndOfRealDurationMarksWatched() {
        assertTrue(DownloadWatchPolicy.shouldMarkWatched(1_400_000L, 1_500_000L))
        assertTrue(DownloadWatchPolicy.hasReallyFinished(1_480_000L, 1_500_000L, 30L))
    }

    @Test
    fun endedShortWindowDoesNotMarkWatched() {
        assertFalse(
            DownloadWatchPolicy.shouldMarkWatched(
                positionMs = 19_000L,
                durationMs = 20_000L,
                playbackEnded = true,
            ),
        )
        assertTrue(
            DownloadWatchPolicy.shouldMarkWatched(
                positionMs = 90_000L,
                durationMs = 95_000L,
                playbackEnded = true,
            ),
        )
    }

    @Test
    fun earlyPositionDoesNotMarkWatched() {
        assertFalse(DownloadWatchPolicy.shouldMarkWatched(30_000L, 1_500_000L, playbackEnded = false))
        assertTrue(DownloadWatchPolicy.hasStarted(25_000L, 1_500_000L))
    }
}
