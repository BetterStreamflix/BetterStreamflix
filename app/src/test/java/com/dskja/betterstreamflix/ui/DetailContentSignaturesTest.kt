package com.dskja.betterstreamflix.ui

import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DetailContentSignaturesTest {

    @Test
    fun movie_favoriteAloneDoesNotChangeSignature() {
        val base = Movie(id = "m1", title = "Alpha", overview = "o")
        base.isFavorite = false
        val favorited = Movie(id = "m1", title = "Alpha", overview = "o")
        favorited.isFavorite = true
        assertEquals(DetailContentSignatures.movie(base), DetailContentSignatures.movie(favorited))
    }

    @Test
    fun movie_watchedChangesSignature() {
        val base = Movie(id = "m1", title = "Alpha")
        base.isWatched = false
        val watched = Movie(id = "m1", title = "Alpha")
        watched.isWatched = true
        assertNotEquals(DetailContentSignatures.movie(base), DetailContentSignatures.movie(watched))
    }

    @Test
    fun tvShow_favoriteAndWatchingAloneDoNotChangeSignature() {
        val base = TvShow(id = "t1", title = "Beta")
        base.isFavorite = false
        base.isWatching = true
        val toggled = TvShow(id = "t1", title = "Beta")
        toggled.isFavorite = true
        toggled.isWatching = false
        assertEquals(DetailContentSignatures.tvShow(base), DetailContentSignatures.tvShow(toggled))
    }

    @Test
    fun tvShow_seasonEpisodeFillChangesSignature() {
        val empty = TvShow(
            id = "t1",
            title = "Beta",
            seasons = listOf(Season(id = "s1", title = "S1", number = 1)),
        )
        val filled = TvShow(
            id = "t1",
            title = "Beta",
            seasons = listOf(
                Season(
                    id = "s1",
                    title = "S1",
                    number = 1,
                    episodes = listOf(Episode(id = "e1", number = 1, title = "E1")),
                ),
            ),
        )
        assertNotEquals(DetailContentSignatures.tvShow(empty), DetailContentSignatures.tvShow(filled))
    }
}
