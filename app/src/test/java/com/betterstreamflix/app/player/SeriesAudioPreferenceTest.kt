package com.betterstreamflix.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesAudioPreferenceTest {

    @Test
    fun canonicalNormalizesIsoVariants() {
        assertEquals("de", SeriesAudioPreference.canonical("deu"))
        assertEquals("de", SeriesAudioPreference.canonical("GER"))
        assertEquals("de", SeriesAudioPreference.canonical("de-DE"))
        assertEquals("en", SeriesAudioPreference.canonical("eng"))
        assertEquals("fr", SeriesAudioPreference.canonical("fra"))
    }

    @Test
    fun canonicalRejectsUnknownTokens() {
        assertEquals(null, SeriesAudioPreference.canonical(null))
        assertEquals(null, SeriesAudioPreference.canonical(""))
        assertEquals(null, SeriesAudioPreference.canonical("und"))
        assertEquals(null, SeriesAudioPreference.canonical("unknown"))
    }

    @Test
    fun matchesAcrossIso639Families() {
        assertTrue(SeriesAudioPreference.matches("de", "deu"))
        assertTrue(SeriesAudioPreference.matches("DE", "ger"))
        assertTrue(SeriesAudioPreference.matches("en", "eng"))
        assertFalse(SeriesAudioPreference.matches("de", "en"))
        assertFalse(SeriesAudioPreference.matches("de", null))
    }

    @Test
    fun displayCodeIsUppercaseIso1() {
        assertEquals("DE", SeriesAudioPreference.displayCode("deu"))
        assertEquals("EN", SeriesAudioPreference.displayCode("en-US"))
        assertEquals("", SeriesAudioPreference.displayCode("und"))
    }

    @Test
    fun exoCodesIncludeCommonAliases() {
        val codes = SeriesAudioPreference.exoCodes("de").toList()
        assertTrue(codes.contains("de") || codes.contains("deu") || codes.contains("ger"))
    }
}
