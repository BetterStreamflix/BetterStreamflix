package com.betterstreamflix.app.smartcollections

import com.betterstreamflix.app.models.Genre
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartCollectionRulesTest {

    @Test
    fun chillMatchesComedyUnderRuntimeCap() {
        val signals = SmartCollectionRules.TitleSignals(
            genres = listOf(Genre("35", "Comedy")),
            runtimeMinutes = 95,
            contentRating = "PG-13",
            originalLanguage = "en",
            providerName = "Sflix",
            seriesId = null,
            preferredAudio = null,
        )
        assertTrue(SmartCollectionRules.matchesChill(signals))
    }

    @Test
    fun chillRejectsHorror() {
        val signals = SmartCollectionRules.TitleSignals(
            genres = listOf(Genre("27", "Horror"), Genre("35", "Comedy")),
            runtimeMinutes = 90,
            contentRating = null,
            originalLanguage = null,
            providerName = null,
            seriesId = null,
            preferredAudio = null,
        )
        assertFalse(SmartCollectionRules.matchesChill(signals))
    }

    @Test
    fun kidsMatchesFamilyGenre() {
        val signals = SmartCollectionRules.TitleSignals(
            genres = listOf(Genre("10751", "Family")),
            runtimeMinutes = 100,
            contentRating = "PG",
            originalLanguage = "en",
            providerName = null,
            seriesId = null,
            preferredAudio = null,
        )
        assertTrue(SmartCollectionRules.matchesKids(signals))
    }

    @Test
    fun kidsRejectsMatureRating() {
        val signals = SmartCollectionRules.TitleSignals(
            genres = listOf(Genre("10751", "Family")),
            runtimeMinutes = 100,
            contentRating = "R",
            originalLanguage = "en",
            providerName = null,
            seriesId = null,
            preferredAudio = null,
        )
        assertFalse(SmartCollectionRules.matchesKids(signals))
    }

    @Test
    fun deAudioMatchesPreferredSeriesAudio() {
        val signals = SmartCollectionRules.TitleSignals(
            genres = emptyList(),
            runtimeMinutes = null,
            contentRating = null,
            originalLanguage = "en",
            providerName = "Sflix",
            seriesId = "show-1",
            preferredAudio = "de",
        )
        assertTrue(SmartCollectionRules.matchesDeAudio(signals))
    }

    @Test
    fun deAudioMatchesOriginalLanguage() {
        val signals = SmartCollectionRules.TitleSignals(
            genres = emptyList(),
            runtimeMinutes = null,
            contentRating = null,
            originalLanguage = "de",
            providerName = "Sflix",
            seriesId = null,
            preferredAudio = null,
        )
        assertTrue(SmartCollectionRules.matchesDeAudio(signals))
    }

    @Test
    fun deAudioMatchesGermanProviderNameHint() {
        val signals = SmartCollectionRules.TitleSignals(
            genres = emptyList(),
            runtimeMinutes = null,
            contentRating = null,
            originalLanguage = "en",
            providerName = "SerienStream",
            seriesId = null,
            preferredAudio = null,
        )
        assertTrue(SmartCollectionRules.matchesDeAudio(signals))
    }

    @Test
    fun deAudioRejectsUnrelatedEnglishTitle() {
        val signals = SmartCollectionRules.TitleSignals(
            genres = emptyList(),
            runtimeMinutes = null,
            contentRating = null,
            originalLanguage = "en",
            providerName = "Sflix",
            seriesId = null,
            preferredAudio = null,
        )
        assertFalse(SmartCollectionRules.matchesDeAudio(signals))
    }
}
