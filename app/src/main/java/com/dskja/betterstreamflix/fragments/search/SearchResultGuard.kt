package com.dskja.betterstreamflix.fragments.search

import android.util.Log
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Genre
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import kotlinx.coroutines.CancellationException

/**
 * Defensive filters for Search lists. German HTML/JSON catalogs sometimes emit
 * blank ids/titles or odd row types that Leanback grid recycling does not tolerate.
 */
object SearchResultGuard {

    fun sanitize(items: List<AppAdapter.Item>): List<AppAdapter.Item> {
        if (items.isEmpty()) return items
        return items.mapNotNull { item ->
            when (item) {
                is Movie -> item.takeIf { it.id.isNotBlank() && it.title.isNotBlank() }
                is TvShow -> item.takeIf { it.id.isNotBlank() && it.title.isNotBlank() }
                is Genre -> item.takeIf { it.id.isNotBlank() && it.name.isNotBlank() }
                else -> item
            }
        }
    }

    fun safeProviderSearch(
        label: String,
        block: () -> List<AppAdapter.Item>,
    ): List<AppAdapter.Item> {
        return try {
            sanitize(block())
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            runCatching { Log.e("SearchResultGuard", "$label failed: ${t.message}", t) }
            emptyList()
        }
    }
}
