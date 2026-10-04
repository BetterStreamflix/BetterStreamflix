package com.dskja.betterstreamflix.fragments.search

import com.dskja.betterstreamflix.models.Genre
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultGuardTest {

    @Test
    fun sanitize_dropsBlankMoviesAndTvShows() {
        val input = listOf(
            Movie(id = "1", title = "Dark"),
            Movie(id = "", title = "NoId"),
            Movie(id = "2", title = ""),
            TvShow(id = "s1", title = "Serie"),
            TvShow(id = "s2", title = "  "),
            Genre(id = "action", name = "Action"),
            Genre(id = "", name = "BlankId"),
            Genre(id = "x", name = ""),
        )

        val out = SearchResultGuard.sanitize(input)

        assertEquals(3, out.size)
        assertTrue(out[0] is Movie && (out[0] as Movie).id == "1")
        assertTrue(out[1] is TvShow && (out[1] as TvShow).id == "s1")
        assertTrue(out[2] is Genre && (out[2] as Genre).name == "Action")
    }

    @Test
    fun sanitize_nullListIsEmpty() {
        assertTrue(SearchResultGuard.sanitize(null).isEmpty())
    }

    @Test
    fun bound_capsAndDropsBlanks() {
        val hits = (1..30).map { Movie(id = "$it", title = "Title $it") } +
            Movie(id = "", title = "drop")
        val out = SearchResultGuard.bound(hits, 4)
        assertEquals(4, out.size)
        assertEquals("1", (out.first() as Movie).id)
    }

    @Test
    fun safeProviderSearch_swallowsThrowableAndReturnsEmpty() {
        val out = SearchResultGuard.safeProviderSearch("boom") {
            error("provider exploded")
        }
        assertTrue(out.isEmpty())
    }

    @Test
    fun safeProviderSearch_keepsValidRows() {
        val out = SearchResultGuard.safeProviderSearch("ok") {
            listOf(
                Movie(id = "1", title = "A"),
                Movie(id = "", title = "drop"),
            )
        }
        assertEquals(1, out.size)
        assertEquals("1", (out.first() as Movie).id)
    }

    @Test
    fun globalSearchConcurrency_isCappedForTv() {
        assertEquals(1, SearchViewModel.GLOBAL_SEARCH_CONCURRENCY)
    }
}
