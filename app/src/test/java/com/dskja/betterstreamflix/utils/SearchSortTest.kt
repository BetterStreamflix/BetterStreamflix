package com.dskja.betterstreamflix.utils

import com.dskja.betterstreamflix.models.Genre
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class SearchSortTest {

    @Test
    fun newestFirstSortsByReleaseDescending() {
        val older = Movie(id = "1", title = "Old").apply {
            released = Calendar.getInstance().apply { set(2010, 0, 1) }
        }
        val newer = Movie(id = "2", title = "New").apply {
            released = Calendar.getInstance().apply { set(2024, 5, 1) }
        }
        val undated = TvShow(id = "3", title = "No date")
        val sorted = SearchSort.items(
            listOf(older, undated, newer),
            SearchSortMode.NEWEST_FIRST,
        )
        assertEquals(listOf("2", "1", "3"), sorted.map {
            when (it) {
                is Movie -> it.id
                is TvShow -> it.id
                else -> ""
            }
        })
    }

    @Test
    fun providerDefaultPreservesOrder() {
        val a = Movie(id = "a", title = "A")
        val b = Movie(id = "b", title = "B")
        val sorted = SearchSort.items(listOf(a, b), SearchSortMode.PROVIDER_DEFAULT)
        assertEquals(listOf("a", "b"), sorted.map { (it as Movie).id })
        assertTrue(SearchSortMode.fromKey("newest_first") == SearchSortMode.NEWEST_FIRST)
    }

    @Test
    fun yearFilterKeepsMatchingShowsAndNonShowItems() {
        val y2020 = Movie(id = "2020", title = "Twenty").apply {
            released = Calendar.getInstance().apply { set(2020, 3, 1) }
        }
        val y2024 = Movie(id = "2024", title = "TwentyFour").apply {
            released = Calendar.getInstance().apply { set(2024, 1, 1) }
        }
        val undated = Movie(id = "none", title = "None")
        val genre = Genre(id = "g", name = "Action")
        val filtered = SearchSort.items(
            listOf(genre, y2020, undated, y2024),
            SearchSortMode.PROVIDER_DEFAULT,
            year = 2024,
        )
        assertEquals(listOf("g", "2024"), filtered.map {
            when (it) {
                is Movie -> it.id
                is Genre -> it.id
                else -> ""
            }
        })
    }

    @Test
    fun yearFilterPlusNewestSortsWithinYear() {
        val jan = Movie(id = "jan", title = "Jan").apply {
            released = Calendar.getInstance().apply { set(2023, 0, 5) }
        }
        val dec = Movie(id = "dec", title = "Dec").apply {
            released = Calendar.getInstance().apply { set(2023, 11, 20) }
        }
        val other = Movie(id = "other", title = "Other").apply {
            released = Calendar.getInstance().apply { set(2022, 5, 1) }
        }
        val sorted = SearchSort.items(
            listOf(jan, other, dec),
            SearchSortMode.NEWEST_FIRST,
            year = 2023,
        )
        assertEquals(listOf("dec", "jan"), sorted.map { (it as Movie).id })
    }

    @Test
    fun yearPickerMergesResultYearsWithWindow() {
        val old = Movie(id = "1", title = "Old").apply {
            released = Calendar.getInstance().apply { set(1985, 0, 1) }
        }
        val years = SearchSort.yearPickerOptions(listOf(old), currentYear = 2026, oldestYear = 2024)
        assertEquals(listOf(2026, 2025, 2024, 1985), years)
    }
}
