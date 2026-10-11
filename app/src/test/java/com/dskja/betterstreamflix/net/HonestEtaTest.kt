package com.dskja.betterstreamflix.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HonestEtaTest {

    @Test
    fun download_usesHistoryOverFantasyLengthAlone() {
        // 10 MiB left at 1 MiB/s history → ~10s
        val estimate = HonestEta.download(
            remainingBytes = 10L * 1024L * 1024L,
            instantaneousBps = 0L,
            historyBps = 1L * 1024L * 1024L,
            historySampleCount = 5,
        )
        assertTrue(estimate.confident)
        assertEquals(1L * 1024L * 1024L, estimate.speedBytesPerSec)
        assertEquals(10L, estimate.etaSeconds)
    }

    @Test
    fun download_unknownRemaining_hidesEta() {
        val estimate = HonestEta.download(
            remainingBytes = 0L,
            instantaneousBps = 5_000_000L,
            historyBps = 5_000_000L,
            historySampleCount = 10,
        )
        assertEquals(-1L, estimate.etaSeconds)
        assertFalse(estimate.confident)
    }

    @Test
    fun download_blendsInstantWithHistory() {
        val estimate = HonestEta.download(
            remainingBytes = 1_000_000L,
            instantaneousBps = 100_000L,
            historyBps = 200_000L,
            historySampleCount = 4,
        )
        // 0.72*200k + 0.28*100k = 172k
        assertEquals(172_000L, estimate.speedBytesPerSec)
        assertTrue(estimate.etaSeconds > 0L)
    }

    @Test
    fun download_coldHistory_usesInstantaneous() {
        val estimate = HonestEta.download(
            remainingBytes = 500_000L,
            instantaneousBps = 100_000L,
            historyBps = null,
            historySampleCount = 0,
        )
        assertEquals(100_000L, estimate.speedBytesPerSec)
        assertEquals(5L, estimate.etaSeconds)
    }

    @Test
    fun buffer_fromDeficitAndBitrate() {
        // Need 4s more media at 8 Mbps; net 1 MB/s → 4s * 1MB = 4s
        val estimate = HonestEta.buffer(
            bufferDeficitMs = 4_000L,
            mediaBitrateBps = 8_000_000,
            historyBps = 1_000_000L,
            historySampleCount = 5,
        )
        assertTrue(estimate.confident)
        assertEquals(4L, estimate.etaSeconds)
    }

    @Test
    fun buffer_coldHistory_unknown() {
        val estimate = HonestEta.buffer(
            bufferDeficitMs = 5_000L,
            mediaBitrateBps = 5_000_000,
            historyBps = null,
            historySampleCount = 0,
        )
        assertEquals(-1L, estimate.etaSeconds)
        assertFalse(estimate.confident)
    }

    @Test
    fun buffer_ready_whenDeficitZero() {
        val estimate = HonestEta.buffer(
            bufferDeficitMs = 0L,
            mediaBitrateBps = 5_000_000,
            historyBps = 1_000_000L,
            historySampleCount = 5,
        )
        assertEquals(0L, estimate.etaSeconds)
        assertTrue(estimate.confident)
    }
}
