package com.dskja.betterstreamflix.utils

import com.dskja.betterstreamflix.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeManagerTest {

    @Test
    fun lockedLooks_isSingleDarkLook() {
        assertEquals(1, ThemeManager.LOCKED_LOOKS.size)
        assertTrue(ThemeManager.LOCKED_LOOKS.contains(ThemeManager.CINEMA_INK))
    }

    @Test
    fun resolveLook_collapsesEverythingToDark() {
        assertEquals(ThemeManager.CINEMA_INK, ThemeManager.resolveLook(ThemeManager.DEFAULT))
        assertEquals(ThemeManager.CINEMA_INK, ThemeManager.resolveLook(ThemeManager.CINEMA_INK))
        assertEquals(ThemeManager.CINEMA_INK, ThemeManager.resolveLook(ThemeManager.CINEMA_INK_LIGHT))
        assertEquals(ThemeManager.CINEMA_INK, ThemeManager.resolveLook(ThemeManager.KIDS_BRIGHT))
        assertEquals(ThemeManager.CINEMA_INK, ThemeManager.resolveLook(ThemeManager.NERO_AMOLED_OLED))
        assertEquals(ThemeManager.CINEMA_INK, ThemeManager.resolveLook(ThemeManager.SUNSET_CINEMA))
        assertEquals(ThemeManager.CINEMA_INK, ThemeManager.resolveLook(null))
        assertEquals(ThemeManager.CINEMA_INK, ThemeManager.resolveLook("unknown_palette"))
    }

    @Test
    fun defaultThemeForKids_isAlwaysDark() {
        assertEquals(ThemeManager.CINEMA_INK, ThemeManager.defaultThemeForKids(true))
        assertEquals(ThemeManager.CINEMA_INK, ThemeManager.defaultThemeForKids(false))
    }

    @Test
    fun clampLookForKids_isAlwaysDark() {
        assertEquals(
            ThemeManager.CINEMA_INK,
            ThemeManager.clampLookForKids(ThemeManager.KIDS_BRIGHT, isKids = true),
        )
        assertEquals(
            ThemeManager.CINEMA_INK,
            ThemeManager.clampLookForKids(ThemeManager.CINEMA_INK_LIGHT, isKids = false),
        )
    }

    @Test
    fun mobileThemeRes_isAlwaysDarkShell() {
        assertEquals(
            R.style.AppTheme_Mobile_CinemaInk,
            ThemeManager.mobileThemeRes(ThemeManager.CINEMA_INK),
        )
        assertEquals(
            R.style.AppTheme_Mobile_CinemaInk,
            ThemeManager.mobileThemeRes(ThemeManager.CINEMA_INK_LIGHT),
        )
        assertEquals(
            R.style.AppTheme_Mobile_CinemaInk,
            ThemeManager.mobileThemeRes(ThemeManager.KIDS_BRIGHT),
        )
        assertEquals(
            R.style.AppTheme_Mobile_CinemaInk,
            ThemeManager.mobileThemeRes(ThemeManager.NERO_AMOLED_OLED),
        )
    }

    @Test
    fun tvThemeRes_isAlwaysDarkShell() {
        assertEquals(
            R.style.AppTheme_CinemaInk,
            ThemeManager.tvThemeRes(ThemeManager.CINEMA_INK),
        )
        assertEquals(
            R.style.AppTheme_CinemaInk,
            ThemeManager.tvThemeRes(ThemeManager.KIDS_BRIGHT),
        )
    }

    @Test
    fun titleRes_isAlwaysDarkLabel() {
        assertEquals(R.string.theme_dark, ThemeManager.titleRes(ThemeManager.CINEMA_INK))
        assertEquals(R.string.theme_dark, ThemeManager.titleRes(ThemeManager.DEFAULT))
        assertEquals(R.string.theme_dark, ThemeManager.titleRes(ThemeManager.KIDS_BRIGHT))
    }

    @Test
    fun isOledActive_alwaysFalse_noHalfMix() {
        @Suppress("DEPRECATION")
        assertFalse(ThemeManager.isOledActive(ThemeManager.CINEMA_INK))
        @Suppress("DEPRECATION")
        assertFalse(ThemeManager.isOledActive(ThemeManager.NERO_AMOLED_OLED))
    }

    @Test
    fun isLockedLook_onlyDark() {
        assertTrue(ThemeManager.isLockedLook(ThemeManager.CINEMA_INK))
        assertFalse(ThemeManager.isLockedLook(ThemeManager.CINEMA_INK_LIGHT))
        assertFalse(ThemeManager.isLockedLook(ThemeManager.KIDS_BRIGHT))
        assertFalse(ThemeManager.isLockedLook(ThemeManager.SUNSET_CINEMA))
        assertFalse(ThemeManager.isLockedLook(ThemeManager.DEFAULT))
    }
}
