package com.dskja.betterstreamflix.utils

import com.dskja.betterstreamflix.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThemeManagerTest {

    @Test
    fun defaultThemeForKids_picksBrightOrCinemaInk() {
        assertEquals(ThemeManager.KIDS_BRIGHT, ThemeManager.defaultThemeForKids(true))
        assertEquals(ThemeManager.CINEMA_INK, ThemeManager.defaultThemeForKids(false))
    }

    @Test
    fun mobileThemeRes_mapsNewProfileThemes() {
        assertEquals(
            R.style.AppTheme_Mobile_CinemaInk,
            ThemeManager.mobileThemeRes(ThemeManager.CINEMA_INK),
        )
        assertEquals(
            R.style.AppTheme_Mobile_KidsBright,
            ThemeManager.mobileThemeRes(ThemeManager.KIDS_BRIGHT),
        )
        assertNotEquals(
            ThemeManager.mobileThemeRes(ThemeManager.CINEMA_INK),
            ThemeManager.mobileThemeRes(ThemeManager.KIDS_BRIGHT),
        )
    }

    @Test
    fun tvThemeRes_mapsNewProfileThemes() {
        assertEquals(
            R.style.AppTheme_CinemaInk,
            ThemeManager.tvThemeRes(ThemeManager.CINEMA_INK),
        )
        assertEquals(
            R.style.AppTheme_KidsBright,
            ThemeManager.tvThemeRes(ThemeManager.KIDS_BRIGHT),
        )
    }

    @Test
    fun titleRes_coversNewThemes() {
        assertEquals(R.string.theme_cinema_ink, ThemeManager.titleRes(ThemeManager.CINEMA_INK))
        assertEquals(R.string.theme_kids_bright, ThemeManager.titleRes(ThemeManager.KIDS_BRIGHT))
    }
}
