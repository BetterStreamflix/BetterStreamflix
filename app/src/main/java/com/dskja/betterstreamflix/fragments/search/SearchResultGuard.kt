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

    /** Local Search stops paging once the grid is holding this many rows. */
    const val MAX_LOCAL_RESULTS = 96

    /** One Global Search provider shelf / section. Keeps DE fan-out off the TV heap. */
    const val MAX_GLOBAL_PER_PROVIDER = 16

    /** Room `IN (:ids)` blows past the SQLite bind limit around 999. */
    const val ROOM_LOOKUP_LIMIT = 400

    fun sanitize(items: List<AppAdapter.Item>?): List<AppAdapter.Item> {
        if (items.isNullOrEmpty()) return emptyList()
        return items.mapNotNull { item ->
            when (item) {
                is Movie -> item.takeIf { it.id.isNotBlank() && it.title.isNotBlank() }
                is TvShow -> item.takeIf { it.id.isNotBlank() && it.title.isNotBlank() }
                is Genre -> item.takeIf { it.id.isNotBlank() && it.name.isNotBlank() }
                else -> item
            }
        }
    }

    fun bound(items: List<AppAdapter.Item>?, limit: Int): List<AppAdapter.Item> =
        sanitize(items).take(limit.coerceAtLeast(0))

    fun safeProviderSearch(
        label: String,
        block: () -> List<AppAdapter.Item>?,
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
