package com.betterstreamflix.app.fragments.search

import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.models.Category
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.People
import com.betterstreamflix.app.models.TvShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMobileRowsTest {

    @Test
    fun local_stampsGridTypesAndCaps() {
        val hits = (1..120).map { Movie(id = "$it", title = "Title $it") } +
            TvShow(id = "", title = "drop")
        val rows = SearchMobileRows.local(hits)
        assertEquals(SearchMobileRows.MAX_LOCAL, rows.size)
        assertEquals(AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM, (rows.first() as Movie).itemType)
    }

    @Test
    fun local_stampsPeopleAndCategoryHeader() {
        val rows = SearchMobileRows.local(
            listOf(
                Category(name = "Similar to Dark", list = emptyList()).apply {
                    identityKey = "search-similar:Dark"
                },
                People(id = "1", name = "Nolan", knownForDepartment = "Directing"),
                Movie(id = "2", title = "Inception"),
            ),
        )
        assertEquals(AppAdapter.Type.CATEGORY_MOBILE_ITEM, (rows[0] as Category).itemType)
        assertEquals(AppAdapter.Type.PEOPLE_MOBILE_ITEM, (rows[1] as People).itemType)
        assertEquals(AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM, (rows[2] as Movie).itemType)
    }

    @Test
    fun global_skipsLoadingErrorAndEmptyProviders() {
        val plan = SearchMobileRows.globalSections(
            rows = listOf(
                "SerienStream" to ProviderResult.State.Loading,
                "AniWorld" to ProviderResult.State.Error(Exception("down")),
                "KinoGer" to ProviderResult.State.Success(listOf(Movie(id = "", title = " "))),
                "FilmPalast" to ProviderResult.State.Success(
                    listOf(Movie(id = "1", title = "Dark"), TvShow(id = "2", title = "Show")),
                ),
            ),
            titleFor = { name, count -> "$name:$count" },
            stillLoading = true,
        )

        assertTrue(plan.stillLoading)
        assertTrue(plan.hasHits)
        assertEquals(3, plan.items.size)
        val header = plan.items.first() as Category
        assertEquals("search-global:FilmPalast", header.identityKey)
        assertEquals(AppAdapter.Type.CATEGORY_MOBILE_ITEM, header.itemType)
        assertTrue(header.list.isEmpty())
        assertEquals(AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM, (plan.items[1] as Movie).itemType)
        assertEquals(AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM, (plan.items[2] as TvShow).itemType)
    }

    @Test
    fun global_finishedWithNoHits() {
        val plan = SearchMobileRows.globalSections(
            rows = listOf("HDFilme" to ProviderResult.State.Success(emptyList())),
            titleFor = { name, _ -> name },
            stillLoading = false,
        )
        assertFalse(plan.stillLoading)
        assertFalse(plan.hasHits)
        assertTrue(plan.items.isEmpty())
    }

    @Test
    fun spanSize_headersUseFullWidth() {
        assertEquals(3, SearchMobileRows.spanSize(AppAdapter.Type.CATEGORY_MOBILE_ITEM.ordinal))
        assertEquals(3, SearchMobileRows.spanSize(AppAdapter.Type.LOADING_ITEM.ordinal))
        assertEquals(1, SearchMobileRows.spanSize(AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM.ordinal))
    }
}
