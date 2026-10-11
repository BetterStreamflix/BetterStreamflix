package com.dskja.betterstreamflix.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickEnergyModeTest {

    @Test
    fun resolveEnabled_autoOnFireTvWhenUnset() {
        assertTrue(StickEnergyMode.resolveEnabled(explicit = null, isAmazonFireTv = true))
        assertFalse(StickEnergyMode.resolveEnabled(explicit = null, isAmazonFireTv = false))
    }

    @Test
    fun resolveEnabled_honorsExplicitToggle() {
        assertFalse(StickEnergyMode.resolveEnabled(explicit = false, isAmazonFireTv = true))
        assertTrue(StickEnergyMode.resolveEnabled(explicit = true, isAmazonFireTv = false))
    }

    @Test
    fun resolveProviderParallelism_serialWhenEnabled() {
        assertEquals(1, StickEnergyMode.resolveProviderParallelism(enabled = true, default = 6))
        assertEquals(3, StickEnergyMode.resolveProviderParallelism(enabled = false, default = 3))
        assertEquals(1, StickEnergyMode.resolveProviderParallelism(enabled = false, default = 0))
    }
}
