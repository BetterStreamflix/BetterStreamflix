package com.dskja.betterstreamflix.utils

import com.dskja.betterstreamflix.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeManagerTest {

    @Test
    fun lockedLooks_areExactlyThree() {
        assertEquals(3, ThemeManager.LOCKED_LOOKS.size)
        assertTrue(ThemeManager.LOCKED_LOOKS.contains(ThemeManager.CINEMA_INK))
        assertTrue(ThemeManager.LOCKED_LOOKS.contains(ThemeManager.CINEMA_INK_LIGHT))
        assertTrue(ThemeManager.LOCKED_LOOKS.contains(ThemeManager.KIDS_BRIGHT))
    }

    @Test
    fun resolveLook_mapsLegacyAndOledToDark() {
        assertEquals(ThemeManager.CINEMA_INK, ThemeManager.resolveLook(ThemeManager.DEFAULT))
        assertEquals(ThemeManager.CINEMA_INK, ThemeManager.resolveLook(ThemeManager.NERO_AMOLED_OLED))
        assertEquals(ThemeManager.CINEMA_INK, ThemeManager.resolveLook(ThemeManager.SUNSET_CINEMA))
        assertEquals(ThemeManager.CINEMA_INK, ThemeManager.resolveLook(ThemeManager.MIDNIGHT_VIOLET))
        assertEquals(ThemeManager.CINEMA_INK, ThemeManager.resolveLook(null))
        assertEquals(ThemeManager.CINEMA_INK, ThemeManager.resolveLook("unknown_palette"))
    }

    @Test
    fun resolveLook_preservesLockedLooks() {
        assertEquals(ThemeManager.CINEMA_INK, ThemeManager.resolveLook(ThemeManager.CINEMA_INK))
        assertEquals(
            ThemeManager.CINEMA_INK_LIGHT,
            ThemeManager.resolveLook(ThemeManager.CINEMA_INK_LIGHT),
        )
        assertEquals(ThemeManager.KIDS_BRIGHT, ThemeManager.resolveLook(ThemeManager.KIDS_BRIGHT))
    }

    @Test
    fun defaultThemeForKids_picksBrightOrCinemaInk() {
        assertEquals(ThemeManager.KIDS_BRIGHT, ThemeManager.defaultThemeForKids(true))
        assertEquals(ThemeManager.CINEMA_INK, ThemeManager.defaultThemeForKids(false))
    }

    @Test
    fun mobileThemeRes_mapsLockedLooksWithoutHalfMix() {
        assertEquals(
            R.style.AppTheme_Mobile_CinemaInk,
            ThemeManager.mobileThemeRes(ThemeManager.CINEMA_INK),
        )
        assertEquals(
            R.style.AppTheme_Mobile_CinemaInkLight,
            ThemeManager.mobileThemeRes(ThemeManager.CINEMA_INK_LIGHT),
        )
        assertEquals(
            R.style.AppTheme_Mobile_KidsBright,
            ThemeManager.mobileThemeRes(ThemeManager.KIDS_BRIGHT),
        )
        // Legacy → Dark shell (not OLED overlay).
        assertEquals(
            R.style.AppTheme_Mobile_CinemaInk,
            ThemeManager.mobileThemeRes(ThemeManager.NERO_AMOLED_OLED),
        )
        assertEquals(
            R.style.AppTheme_Mobile_CinemaInk,
            ThemeManager.mobileThemeRes(ThemeManager.SUNSET_CINEMA),
        )
        assertNotEquals(
            ThemeManager.mobileThemeRes(ThemeManager.CINEMA_INK),
            ThemeManager.mobileThemeRes(ThemeManager.CINEMA_INK_LIGHT),
        )
    }

    @Test
    fun tvThemeRes_mapsLockedLooks() {
        assertEquals(
            R.style.AppTheme_CinemaInk,
            ThemeManager.tvThemeRes(ThemeManager.CINEMA_INK),
        )
        assertEquals(
            R.style.AppTheme_CinemaInkLight,
            ThemeManager.tvThemeRes(ThemeManager.CINEMA_INK_LIGHT),
        )
        assertEquals(
            R.style.AppTheme_KidsBright,
            ThemeManager.tvThemeRes(ThemeManager.KIDS_BRIGHT),
        )
    }

    @Test
    fun titleRes_coversLockedLooks() {
        assertEquals(R.string.theme_cinema_ink, ThemeManager.titleRes(ThemeManager.CINEMA_INK))
        assertEquals(
            R.string.theme_cinema_ink_light,
            ThemeManager.titleRes(ThemeManager.CINEMA_INK_LIGHT),
        )
        assertEquals(R.string.theme_kids_bright, ThemeManager.titleRes(ThemeManager.KIDS_BRIGHT))
        // Legacy titles collapse to Dark.
        assertEquals(R.string.theme_cinema_ink, ThemeManager.titleRes(ThemeManager.DEFAULT))
    }

    @Test
    fun isOledActive_alwaysFalse_noHalfMix() {
        @Suppress("DEPRECATION")
        assertFalse(ThemeManager.isOledActive(ThemeManager.CINEMA_INK))
        @Suppress("DEPRECATION")
        assertFalse(ThemeManager.isOledActive(ThemeManager.NERO_AMOLED_OLED))
        @Suppress("DEPRECATION")
        assertFalse(ThemeManager.isOledActive(ThemeManager.SUNSET_CINEMA))
    }

    @Test
    fun isLockedLook_onlyThree() {
        assertTrue(ThemeManager.isLockedLook(ThemeManager.CINEMA_INK))
        assertTrue(ThemeManager.isLockedLook(ThemeManager.CINEMA_INK_LIGHT))
        assertTrue(ThemeManager.isLockedLook(ThemeManager.KIDS_BRIGHT))
        assertFalse(ThemeManager.isLockedLook(ThemeManager.SUNSET_CINEMA))
        assertFalse(ThemeManager.isLockedLook(ThemeManager.DEFAULT))
    }
}
