package com.dskja.betterstreamflix.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecComfortTest {

    @Test
    fun resolveActive_requiresThreshold() {
        assertFalse(CodecComfort.resolveActive(0))
        assertFalse(CodecComfort.resolveActive(1))
        assertTrue(CodecComfort.resolveActive(2))
        assertTrue(CodecComfort.resolveActive(CodecComfort.FAILURES_BEFORE_ACTIVE))
        assertTrue(CodecComfort.resolveActive(10))
    }

    @Test
    fun deviceFingerprint_includesSdkAndNormalizes() {
        val fp = CodecComfort.deviceFingerprint(
            manufacturer = " Amazon ",
            model = "AFTMM",
            device = "mantis",
            sdkInt = 28,
        )
        assertEquals("amazon|aftmm|mantis|sdk28", fp)
    }

    @Test
    fun deviceFingerprint_sdkOnlyWhenHardwareBlank() {
        assertEquals("sdk30", CodecComfort.deviceFingerprint("", "", "", sdkInt = 30))
    }

    @Test
    fun failuresBeforeActive_isTwo() {
        assertEquals(2, CodecComfort.FAILURES_BEFORE_ACTIVE)
    }

    @Test
    fun resolveActive_customThreshold() {
        assertFalse(CodecComfort.resolveActive(2, threshold = 3))
        assertTrue(CodecComfort.resolveActive(3, threshold = 3))
    }

    @Test
    fun stableSessionsToDecay_isThree() {
        assertEquals(3, CodecComfort.STABLE_SESSIONS_TO_DECAY)
    }

    /** CC-012: fingerprint always includes sdk$N so ROM upgrades reset the latch. */
    @Test
    fun deviceFingerprint_sdkDifferentiatesSameHardware() {
        val a = CodecComfort.deviceFingerprint("Amazon", "AFTMM", "mantis", sdkInt = 28)
        val b = CodecComfort.deviceFingerprint("Amazon", "AFTMM", "mantis", sdkInt = 30)
        assertEquals("amazon|aftmm|mantis|sdk28", a)
        assertEquals("amazon|aftmm|mantis|sdk30", b)
        assertTrue(a != b)
    }

    /**
     * CC-012: decay math — below threshold clears active; streak threshold is 3.
     * (Persistence path needs instrumentation; lock pure resolveActive ladder here.)
     */
    @Test
    fun resolveActive_decayLadderMatchesThreshold() {
        assertFalse(CodecComfort.resolveActive(CodecComfort.FAILURES_BEFORE_ACTIVE - 1))
        assertTrue(CodecComfort.resolveActive(CodecComfort.FAILURES_BEFORE_ACTIVE))
        // Soft decay drops one failure after STABLE_SESSIONS_TO_DECAY clean HW sessions.
        assertEquals(3, CodecComfort.STABLE_SESSIONS_TO_DECAY)
        assertEquals(24, CodecComfort.MAX_FAILURES_TRACKED)
    }
}
