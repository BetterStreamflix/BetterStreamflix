package com.dskja.betterstreamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeManagerTest {

    @Test
    fun canonicalizeFoldsLegacySkinsIntoFourLooks() {
        assertEquals(ThemeManager.DEFAULT, ThemeManager.canonicalize("sunset_cinema"))
        assertEquals(ThemeManager.DEFAULT, ThemeManager.canonicalize("crimson_noir"))
        assertEquals(ThemeManager.DEFAULT, ThemeManager.canonicalize("nero_amoled_oled"))
        assertEquals(ThemeManager.MIDNIGHT_VIOLET, ThemeManager.canonicalize("retro_neon"))
        assertEquals(ThemeManager.FOREST_NIGHT, ThemeManager.canonicalize("emerald_luxe"))
        assertEquals(ThemeManager.STEEL_BLUE, ThemeManager.canonicalize("nord_frost"))
        assertEquals(ThemeManager.DEFAULT, ThemeManager.canonicalize("unknown"))
    }

    @Test
    fun accentKeyMapsLegacySkinsOntoNocturneAccents() {
        assertEquals("copper", ThemeManager.accentKey("default"))
        assertEquals("copper", ThemeManager.accentKey("sunset_cinema"))
        assertEquals("copper", ThemeManager.accentKey("crimson_noir"))
        assertEquals("violet", ThemeManager.accentKey("midnight_violet"))
        assertEquals("violet", ThemeManager.accentKey("retro_neon"))
        assertEquals("moss", ThemeManager.accentKey("forest_night"))
        assertEquals("moss", ThemeManager.accentKey("emerald_luxe"))
        assertEquals("ink", ThemeManager.accentKey("steel_blue"))
        assertEquals("ink", ThemeManager.accentKey("nord_frost"))
    }

    @Test
    fun themeForAccentRoundTripsCanonicalLooks() {
        assertEquals(
            ThemeManager.DEFAULT,
            ThemeManager.themeForAccent(ExperimentalMobileDesign.Accent.COPPER),
        )
        assertEquals(
            ThemeManager.MIDNIGHT_VIOLET,
            ThemeManager.themeForAccent(ExperimentalMobileDesign.Accent.VIOLET),
        )
        assertEquals(
            ThemeManager.FOREST_NIGHT,
            ThemeManager.themeForAccent(ExperimentalMobileDesign.Accent.MOSS),
        )
        assertEquals(
            ThemeManager.STEEL_BLUE,
            ThemeManager.themeForAccent(ExperimentalMobileDesign.Accent.INK),
        )
    }

    @Test
    fun titleResUsesCanonicalNocturneNames() {
        assertEquals(com.dskja.betterstreamflix.R.string.theme_default, ThemeManager.titleRes("sunset_cinema"))
        assertEquals(com.dskja.betterstreamflix.R.string.theme_midnight_violet, ThemeManager.titleRes("retro_neon"))
        assertEquals(com.dskja.betterstreamflix.R.string.theme_forest_night, ThemeManager.titleRes("emerald_luxe"))
        assertEquals(com.dskja.betterstreamflix.R.string.theme_steel_blue, ThemeManager.titleRes("nord_frost"))
    }
}
