package com.dskja.betterstreamflix.fragments.search

import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Movie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchCategoryIdentityTest {

    @Test
    fun identityKeySurvivesCopyAndDiffersFromTransientTitle() {
        val loading = Category(
            name = "ProviderA - Searching",
            list = emptyList(),
        ).apply {
            identityKey = "search-global:ProviderA"
            itemType = AppAdapter.Type.CATEGORY_TV_ITEM
        }
        val success = loading.copy(
            name = "ProviderA - 3 results",
            list = listOf(Movie(id = "1", title = "One")),
        )

        assertEquals("search-global:ProviderA", success.identityKey)
        assertEquals(AppAdapter.Type.CATEGORY_TV_ITEM, success.itemType)
        assertNotEquals(loading.name, success.name)
        // DiffUtil base key must stay stable when only the status title changes.
        assertEquals(loading.identityKey, success.identityKey)
    }

    @Test
    fun emptyGlobalShelfKeepsIdentityWithoutResults() {
        val error = Category(
            name = "ProviderB - Error",
            list = emptyList(),
        ).apply {
            identityKey = "search-global:ProviderB"
            itemType = AppAdapter.Type.CATEGORY_TV_ITEM
        }
        assertTrue(error.list.isEmpty())
        assertEquals("search-global:ProviderB", error.identityKey)
        assertEquals(AppAdapter.Type.CATEGORY_TV_ITEM, error.itemType)
    }
}
