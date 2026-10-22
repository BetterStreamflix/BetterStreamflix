package com.betterstreamflix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickEnergyModeTest {

    @Test
    fun resolveEnabled_autoOnWhenUnsetAndAutoEligible() {
        assertTrue(StickEnergyMode.resolveEnabled(explicit = null, autoOn = true))
        assertFalse(StickEnergyMode.resolveEnabled(explicit = null, autoOn = false))
    }

    @Test
    fun resolveEnabled_honorsExplicitToggle() {
        assertFalse(StickEnergyMode.resolveEnabled(explicit = false, autoOn = true))
        assertTrue(StickEnergyMode.resolveEnabled(explicit = true, autoOn = false))
    }

    @Test
    fun resolveProviderParallelism_serialWhenEnabled() {
        assertEquals(1, StickEnergyMode.resolveProviderParallelism(enabled = true, default = 6))
        assertEquals(3, StickEnergyMode.resolveProviderParallelism(enabled = false, default = 3))
        assertEquals(1, StickEnergyMode.resolveProviderParallelism(enabled = false, default = 0))
    }

    @Test
    fun resolveZapWarmRadius_capsUnderEnergy() {
        assertEquals(1, StickEnergyMode.resolveZapWarmRadius(enabled = true, default = 2))
        assertEquals(0, StickEnergyMode.resolveZapWarmRadius(enabled = true, default = 0))
        assertEquals(2, StickEnergyMode.resolveZapWarmRadius(enabled = false, default = 2))
    }
}
