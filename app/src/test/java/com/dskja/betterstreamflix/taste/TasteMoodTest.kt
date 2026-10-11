package com.dskja.betterstreamflix.taste

import com.dskja.betterstreamflix.models.Genre
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.smartcollections.SmartCollectionRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteMoodTest {

    @Test
    fun intenseMatchesHorror() {
        val signals = SmartCollectionRules.TitleSignals(
            genres = listOf(Genre("27", "Horror")),
            runtimeMinutes = 100,
            contentRating = "R",
            originalLanguage = "en",
            providerName = null,
            seriesId = null,
            preferredAudio = null,
        )
        assertTrue(TasteMoodMatcher.matchesIntense(signals))
    }

    @Test
    fun kidsMoodUsesSmartRules() {
        val kids = Movie(
            id = "1",
            title = "Family",
            genres = listOf(Genre("10751", "Family")),
        ).apply { contentRating = "PG" }
        val adult = Movie(
            id = "2",
            title = "Crime",
            genres = listOf(Genre("80", "Crime")),
        ).apply { contentRating = "R" }
        assertTrue(TasteMoodMatcher.matches(kids, TasteMood.KIDS))
        assertFalse(TasteMoodMatcher.matches(adult, TasteMood.KIDS))
    }

    @Test
    fun allMoodPassesEverything() {
        val movie = Movie(id = "1", title = "Anything")
        assertTrue(TasteMoodMatcher.matches(movie, TasteMood.ALL))
    }
}
