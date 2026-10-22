package com.betterstreamflix.app.fragments.search

import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.models.Category
import com.betterstreamflix.app.models.Movie
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
