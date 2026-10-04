package com.dskja.betterstreamflix.fragments.search

import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Genre
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTvRowsTest {

    @Test
    fun localBlankQuery_usesGenreColumnsAndDropsBlanks() {
        val plan = SearchTvRows.local(
            query = "",
            items = listOf(
                Genre(id = "action", name = "Action"),
                Genre(id = "", name = "Drop"),
                Movie(id = "m1", title = "Film"),
            ),
        )

        assertEquals(SearchTvRows.GENRE_COLUMNS, plan.columns)
        assertEquals(2, plan.items.size)
        assertEquals(AppAdapter.Type.GENRE_GRID_TV_ITEM, (plan.items[0] as Genre).itemType)
        assertEquals(AppAdapter.Type.MOVIE_GRID_TV_ITEM, (plan.items[1] as Movie).itemType)
        assertTrue(plan.hasFocusableContent)
    }

    @Test
    fun localTypedQuery_usesResultColumns() {
        val plan = SearchTvRows.local(
            query = "dark",
            items = listOf(TvShow(id = "s1", title = "Dark")),
        )
        assertEquals(SearchTvRows.RESULT_COLUMNS, plan.columns)
        assertEquals(AppAdapter.Type.TV_SHOW_GRID_TV_ITEM, (plan.items.single() as TvShow).itemType)
    }

    @Test
    fun global_omitsLoadingErrorAndEmptyShelves() {
        val plan = SearchTvRows.globalShelves(
            rows = listOf(
                "LoadingCo" to ProviderResult.State.Loading,
                "ErrorCo" to ProviderResult.State.Error(Exception("down")),
                "EmptyCo" to ProviderResult.State.Success(
                    listOf(Movie(id = "", title = "blank")),
                ),
                "HitCo" to ProviderResult.State.Success(
                    listOf(Movie(id = "1", title = "Dark"), TvShow(id = "2", title = "Show")),
                ),
            ),
            titleFor = { name, count -> "$name:$count" },
        )

        assertEquals(SearchTvRows.GLOBAL_COLUMNS, plan.columns)
        assertEquals(1, plan.items.size)
        val shelf = plan.items.single() as Category
        assertEquals("search-global:HitCo", shelf.identityKey)
        assertEquals("HitCo:2", shelf.name)
        assertEquals(AppAdapter.Type.CATEGORY_TV_ITEM, shelf.itemType)
        assertEquals(AppAdapter.Type.MOVIE_TV_ITEM, (shelf.list[0] as Movie).itemType)
        assertEquals(AppAdapter.Type.TV_SHOW_TV_ITEM, (shelf.list[1] as TvShow).itemType)
    }

    @Test
    fun global_capsPerProvider() {
        val hits = (1..40).map { Movie(id = "$it", title = "Title $it") }
        val plan = SearchTvRows.globalShelves(
            rows = listOf("Cap" to ProviderResult.State.Success(hits)),
            titleFor = { _, count -> count.toString() },
        )
        val shelf = plan.items.single() as Category
        assertEquals(SearchTvRows.MAX_GLOBAL_PER_PROVIDER, shelf.list.size)
        assertEquals(SearchTvRows.MAX_GLOBAL_PER_PROVIDER.toString(), shelf.name)
    }
}
