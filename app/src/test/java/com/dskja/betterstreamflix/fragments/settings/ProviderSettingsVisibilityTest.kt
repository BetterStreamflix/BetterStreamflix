package com.dskja.betterstreamflix.fragments.settings

import com.dskja.betterstreamflix.providers.TmdbProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSettingsVisibilityTest {

    @Test
    fun nullProviderHidesProviderSpecificSurfaces() {
        assertFalse(ProviderSettingsVisibility.showSerienStreamSettings(null))
        assertFalse(ProviderSettingsVisibility.showAniWorldSettings(null))
        assertFalse(ProviderSettingsVisibility.showWatchlistImport(null))
        assertFalse(ProviderSettingsVisibility.supportsAccountLogin(null))
    }

    @Test
    fun watchlistImportForTmdbDeutschOnlyAmongTmdbLocales() {
        assertTrue(ProviderSettingsVisibility.showWatchlistImport(TmdbProvider.forLanguage("de")))
        assertTrue(ProviderSettingsVisibility.isTmdbDeutsch(TmdbProvider.forLanguage("de")))
        assertFalse(ProviderSettingsVisibility.showWatchlistImport(TmdbProvider.forLanguage("en")))
        assertFalse(ProviderSettingsVisibility.showSerienStreamSettings(TmdbProvider.forLanguage("de")))
        assertFalse(ProviderSettingsVisibility.showAniWorldSettings(TmdbProvider.forLanguage("de")))
        assertFalse(ProviderSettingsVisibility.supportsAccountLogin(TmdbProvider.forLanguage("de")))
    }
}
