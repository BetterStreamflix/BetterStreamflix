package com.betterstreamflix.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ExperimentalMobileDesignTest {

    @Test
    fun luminaIsPermanentlyDisabled() {
        assertFalse(ExperimentalMobileDesign.enabled())
    }

    @Test
    fun classicFallbacksAreStable() {
        // Legacy callers keep compiling against classic resources.
        assertEquals(
            com.betterstreamflix.app.R.drawable.bg_detail_icon_chip,
            ExperimentalMobileDesign.iconChipBackground(),
        )
    }
}
