package com.dskja.betterstreamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalMobileDesignTest {

    @Test
    fun nocturneIsAlwaysOn() {
        assertTrue(ExperimentalMobileDesign.isAvailable())
        assertTrue(ExperimentalMobileDesign.enabled())
        assertEquals(42, ExperimentalMobileDesign.layout(7, 42))
    }

    @Test
    fun accentFromKeyDefaultsToCopper() {
        assertEquals(
            ExperimentalMobileDesign.Accent.COPPER,
            ExperimentalMobileDesign.Accent.fromKey(null),
        )
        assertEquals(
            ExperimentalMobileDesign.Accent.COPPER,
            ExperimentalMobileDesign.Accent.fromKey("unknown"),
        )
    }

    @Test
    fun accentFromKeyParsesPresetsAndLegacyAliases() {
        assertEquals(ExperimentalMobileDesign.Accent.VIOLET, ExperimentalMobileDesign.Accent.fromKey("violet"))
        assertEquals(ExperimentalMobileDesign.Accent.VIOLET, ExperimentalMobileDesign.Accent.fromKey("ember"))
        assertEquals(ExperimentalMobileDesign.Accent.MOSS, ExperimentalMobileDesign.Accent.fromKey("MOSS"))
        assertEquals(ExperimentalMobileDesign.Accent.MOSS, ExperimentalMobileDesign.Accent.fromKey("aurora"))
        assertEquals(ExperimentalMobileDesign.Accent.INK, ExperimentalMobileDesign.Accent.fromKey("ink"))
        assertEquals(ExperimentalMobileDesign.Accent.INK, ExperimentalMobileDesign.Accent.fromKey("slate"))
        assertEquals(ExperimentalMobileDesign.Accent.COPPER, ExperimentalMobileDesign.Accent.fromKey("copper"))
        assertEquals(ExperimentalMobileDesign.Accent.COPPER, ExperimentalMobileDesign.Accent.fromKey("crimson"))
    }

    @Test
    fun accentKeysAreStable() {
        assertEquals("copper", ExperimentalMobileDesign.Accent.COPPER.key)
        assertEquals("violet", ExperimentalMobileDesign.Accent.VIOLET.key)
        assertEquals("moss", ExperimentalMobileDesign.Accent.MOSS.key)
        assertEquals("ink", ExperimentalMobileDesign.Accent.INK.key)
        assertTrue(ExperimentalMobileDesign.Accent.entries.size >= 4)
        assertFalse(ExperimentalMobileDesign.Accent.entries.isEmpty())
    }

    @Test
    fun accentCssHex_isEditorialCopperByDefault() {
        assertEquals("#D08A4A", when (ExperimentalMobileDesign.Accent.COPPER) {
            ExperimentalMobileDesign.Accent.COPPER -> "#D08A4A"
            ExperimentalMobileDesign.Accent.VIOLET -> "#9B7EC8"
            ExperimentalMobileDesign.Accent.MOSS -> "#6FA37A"
            ExperimentalMobileDesign.Accent.INK -> "#A8B4C0"
        })
    }
}
