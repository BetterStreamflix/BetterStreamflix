package com.dskja.betterstreamflix.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WatchPartySyncHealthTest {

    @Test
    fun levelsByDriftAndStaleHost() {
        assertEquals(
            WatchPartySyncHealth.Level.GOOD,
            WatchPartySyncHealth.levelFor(500L, 1_000L),
        )
        assertEquals(
            WatchPartySyncHealth.Level.LAGGING,
            WatchPartySyncHealth.levelFor(4_000L, 1_000L),
        )
        assertEquals(
            WatchPartySyncHealth.Level.LOST,
            WatchPartySyncHealth.levelFor(12_000L, 1_000L),
        )
        assertEquals(
            WatchPartySyncHealth.Level.STALE,
            WatchPartySyncHealth.levelFor(100L, WatchTogetherPro.HOST_STALE_MS + 1),
        )
    }

    @Test
    fun driftAndHostAge() {
        assertEquals(2_500L, WatchPartySyncHealth.driftBetween(1_000L, 3_500L))
        val now = System.currentTimeMillis()
        val updated = Instant.ofEpochMilli(now - 3_000L).toString()
        val age = WatchPartySyncHealth.hostAgeMs(updated, now)
        assertTrue(age != null && age in 2_500L..3_500L)
    }

    @Test
    fun shortCodeNormalize() {
        assertEquals("ABCDE2", WatchPartyLinks.normalizeShortCode("abcde2"))
        assertEquals("OII234", WatchPartyLinks.normalizeShortCode("011234"))
        assertTrue(WatchPartyLinks.isValidShortCode("AB2C3D"))
        assertEquals(6, WatchPartyLinks.newShortCode().length)
    }
}
