package com.betterstreamflix.app.taste

import com.betterstreamflix.app.models.Genre
import com.betterstreamflix.app.models.Movie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteRankerTest {

    @Test
    fun dropsNegatives() {
        val keep = Movie(id = "1", title = "Keep", genres = listOf(Genre("35", "Comedy")))
        val hide = Movie(id = "2", title = "Hide", genres = listOf(Genre("35", "Comedy")))
        val snap = TasteSnapshot(negatives = setOf("movie:2"))
        val ranked = TasteRanker.apply(listOf(keep, hide), snap)
        assertEquals(listOf("1"), ranked.map { (it as Movie).id })
    }

    @Test
    fun boostsGenreAffinityAndFavorite() {
        val chill = Movie(
            id = "c",
            title = "Chill",
            genres = listOf(Genre("35", "Comedy")),
        )
        val fav = Movie(
            id = "f",
            title = "Fav",
            genres = listOf(Genre("28", "Action")),
            isFavorite = true,
        )
        val snap = TasteSnapshot(genreAffinity = mapOf("35" to 2.5f))
        val ranked = TasteRanker.apply(
            listOf(fav, chill),
            snap,
            mood = TasteMood.ALL,
        )
        // Favorite (+2) vs genre affinity (+2.5) → chill first
        assertEquals("c", (ranked.first() as Movie).id)
    }

    @Test
    fun moodFiltersIntense() {
        val horror = Movie(
            id = "h",
            title = "Scary",
            genres = listOf(Genre("27", "Horror")),
        )
        val comedy = Movie(
            id = "c",
            title = "Funny",
            genres = listOf(Genre("35", "Comedy")),
        )
        val ranked = TasteRanker.apply(
            listOf(comedy, horror),
            TasteSnapshot(),
            mood = TasteMood.INTENSE,
        )
        assertEquals(listOf("h"), ranked.map { (it as Movie).id })
    }

    @Test
    fun forYouStableTieBreakPreservesRelativeOrder() {
        val a = Movie(id = "a", title = "A")
        val b = Movie(id = "b", title = "B")
        val ranked = TasteRanker.apply(listOf(a, b), TasteSnapshot())
        assertEquals(listOf("a", "b"), ranked.map { (it as Movie).id })
    }

    @Test
    fun scoreNegativeIsNegInfinity() {
        val movie = Movie(id = "x", title = "X")
        val score = TasteRanker.score(
            movie,
            TasteSnapshot(negatives = setOf("movie:x")),
        )
        assertTrue(score.isInfinite() && score < 0)
        assertFalse(TasteRanker.apply(listOf(movie), TasteSnapshot(negatives = setOf("movie:x"))).contains(movie))
    }
}
