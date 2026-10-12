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
    fun deviceFingerprint_normalizesCaseAndJoins() {
        val fp = CodecComfort.deviceFingerprint(
            manufacturer = " Amazon ",
            model = "AFTMM",
            device = "mantis",
        )
        assertEquals("amazon|aftmm|mantis", fp)
    }

    @Test
    fun deviceFingerprint_blankFallsBack() {
        assertEquals("unknown", CodecComfort.deviceFingerprint("", "", ""))
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
}
