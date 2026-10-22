package com.betterstreamflix.app.utils

import com.betterstreamflix.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityPreferencesTest {

    @Test
    fun largeTextScale_isAboveSystemDefault() {
        assertTrue(AccessibilityPreferences.LARGE_TEXT_SCALE > 1.0f)
        assertEquals(1.3f, AccessibilityPreferences.LARGE_TEXT_SCALE, 0.001f)
    }

    @Test
    fun highContrastOverlay_differsForMobileAndTv() {
        assertEquals(
            R.style.AppTheme_Mobile_HighContrastOverlay,
            AccessibilityPreferences.highContrastOverlay(mobile = true),
        )
        assertEquals(
            R.style.AppTheme_Tv_HighContrastOverlay,
            AccessibilityPreferences.highContrastOverlay(mobile = false),
        )
        assertTrue(
            AccessibilityPreferences.highContrastOverlay(true) !=
                AccessibilityPreferences.highContrastOverlay(false),
        )
    }

    @Test
    fun isHighContrastActive_defaultsFalseWithoutPrefs() {
        assertFalse(AccessibilityPreferences.isHighContrastActive())
    }
}
