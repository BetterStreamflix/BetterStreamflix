package com.dskja.betterstreamflix.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TmdbProviderIdentityTest {

    @Test
    fun forLanguageReturnsStableInstancePerLanguage() {
        val a = TmdbProvider.forLanguage("en")
        val b = TmdbProvider.forLanguage("EN")
        val c = TmdbProvider.forLanguage("English")
        assertSame(a, b)
        assertSame(a, c)
        assertEquals("TMDb (en)", a.name)
    }

    @Test
    fun invokeUsesStableCache() {
        val a = TmdbProvider("de")
        val b = TmdbProvider.forLanguage("Deutsch")
        assertSame(a, b)
        assertEquals("TMDb (de)", a.name)
    }

    @Test
    fun equalsMatchesLanguageEvenIfComparedAsProvider() {
        val a: Provider = TmdbProvider.forLanguage("fr")
        val b: Provider = TmdbProvider.forLanguage("français")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, TmdbProvider.forLanguage("en"))
    }

    @Test
    fun displayNameAndCodeNormalizeToSameInstance() {
        // Mimics UserPreferences getter parsing "TMDb (en)" without loading Provider registry.
        val fromPrefsToken = TmdbProvider.forLanguage("en")
        val fromUiCode = TmdbProvider("en")
        assertSame(fromPrefsToken, fromUiCode)
        assertEquals(fromPrefsToken, fromUiCode)
    }
}
