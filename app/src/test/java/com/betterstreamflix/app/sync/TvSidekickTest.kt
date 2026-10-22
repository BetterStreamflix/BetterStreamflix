package com.betterstreamflix.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvSidekickTest {

    @Test
    fun relativeSeekClampsToZero() {
        assertEquals(0L, TvSidekick.relativeSeek(5_000L, -30_000L))
    }

    @Test
    fun relativeSeekClampsToDuration() {
        assertEquals(
            60_000L,
            TvSidekick.relativeSeek(55_000L, TvSidekick.SEEK_STEP_LONG_MS, durationMs = 60_000L),
        )
    }

    @Test
    fun formatAndParseMmSs() {
        assertEquals("1:05", TvSidekick.formatPosition(65_000L))
        assertEquals(65_000L, TvSidekick.parsePositionInput("1:05"))
        assertEquals(3_665_000L, TvSidekick.parsePositionInput("1:01:05"))
        assertEquals(90_000L, TvSidekick.parsePositionInput("90"))
        assertEquals(10_000L, TvSidekick.parsePositionInput("90", durationMs = 10_000L))
    }

    @Test
    fun bareDigitsAreSeconds() {
        assertEquals(90_000L, TvSidekick.parsePositionInput("90"))
        assertEquals(0L, TvSidekick.parsePositionInput("0"))
        assertNull(TvSidekick.parsePositionInput(""))
    }

    @Test
    fun sanitizeKeyboardTrimsAndCaps() {
        assertNull(TvSidekick.sanitizeKeyboard("   "))
        val long = "x".repeat(120)
        assertEquals(80, TvSidekick.sanitizeKeyboard(long)?.length)
        assertEquals("Dune", TvSidekick.sanitizeKeyboard("  Dune  "))
    }

    @Test
    fun sanitizeDisplayNameDefaultsGuest() {
        assertEquals("Guest", TvSidekick.sanitizeDisplayName("   "))
        assertEquals("Alex", TvSidekick.sanitizeDisplayName(" Alex "))
    }

    @Test
    fun chatReactionsCurated() {
        assertTrue(TvSidekick.isAllowedReaction("🔥"))
        assertFalse(TvSidekick.isAllowedReaction("☠️"))
    }

    @Test
    fun seekApplyUsesSequence() {
        assertTrue(TvSidekick.shouldApplySeek(2L, 1L, 12_000L))
        assertFalse(TvSidekick.shouldApplySeek(1L, 1L, 12_000L))
        assertFalse(TvSidekick.shouldApplySeek(3L, 1L, null))
    }

    @Test
    fun keyboardApplyUsesSequence() {
        assertTrue(TvSidekick.shouldApplyKeyboard(4L, 2L, "Alien"))
        assertFalse(TvSidekick.shouldApplyKeyboard(2L, 2L, "Alien"))
        assertFalse(TvSidekick.shouldApplyKeyboard(5L, 2L, "  "))
    }
}
