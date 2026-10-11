package com.dskja.betterstreamflix.fragments.search

import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Movie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mobile row plan must drop orphan Category headers (mood/negatives emptied posters).
 */
class SearchOrderLocalHeadersTest {

    @Test
    fun mobileRowsDropOrphanCategoryHeaders() {
        val header = Category(name = "Similar to X", list = emptyList())
        val plan = SearchMobileRows.local(listOf(header))
        assertTrue(plan.none { it is Category })
    }

    @Test
    fun mobileRowsKeepHeaderWhenShowsFollow() {
        val header = Category(name = "Similar to X", list = emptyList())
        val movie = Movie(id = "1", title = "A")
        val plan = SearchMobileRows.local(listOf(header, movie))
        assertEquals(2, plan.size)
        assertTrue(plan.first() is Category)
    }
}
