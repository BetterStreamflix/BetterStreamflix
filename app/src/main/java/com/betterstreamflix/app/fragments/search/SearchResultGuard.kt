package com.betterstreamflix.app.fragments.search

import android.content.Context
import android.util.Log
import com.betterstreamflix.app.BetterStreamflixApp
import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.models.Category
import com.betterstreamflix.app.models.Genre
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.People
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.utils.DeviceCapabilities
import kotlinx.coroutines.CancellationException

/**
 * Defensive filters for Search lists. German HTML/JSON catalogs sometimes emit
 * blank ids/titles or odd row types that Leanback grid recycling does not tolerate.
 */
object SearchResultGuard {

    /** Local Search stops paging once the grid is holding this many rows. */
    const val MAX_LOCAL_RESULTS = 96

    /** Constrained TV (Fire Stick / low-RAM) — keep heap under OOM pressure. */
    const val MAX_LOCAL_RESULTS_CONSTRAINED = 48

    /** One Global Search provider shelf / section. Keeps DE fan-out off the TV heap. */
    const val MAX_GLOBAL_PER_PROVIDER = 16

    const val MAX_GLOBAL_PER_PROVIDER_CONSTRAINED = 10

    /** Room `IN (:ids)` blows past the SQLite bind limit around 999. */
    const val ROOM_LOOKUP_LIMIT = 400

    fun maxLocalResults(context: Context? = null): Int {
        val ctx = context ?: runCatching { BetterStreamflixApp.instance.applicationContext }.getOrNull()
        return if (ctx != null && isConstrainedTv(ctx)) MAX_LOCAL_RESULTS_CONSTRAINED
        else MAX_LOCAL_RESULTS
    }

    fun maxGlobalPerProvider(context: Context? = null): Int {
        val ctx = context ?: runCatching { BetterStreamflixApp.instance.applicationContext }.getOrNull()
        return if (ctx != null && isConstrainedTv(ctx)) MAX_GLOBAL_PER_PROVIDER_CONSTRAINED
        else MAX_GLOBAL_PER_PROVIDER
    }

    private fun isConstrainedTv(context: Context): Boolean =
        DeviceCapabilities.shouldUseConstrainedPlayback(context) ||
            DeviceCapabilities.isLowRamDevice(context) ||
            (
                DeviceCapabilities.isLeanbackDevice(context) &&
                    (
                        DeviceCapabilities.isAmazonFireTv(context) ||
                            DeviceCapabilities.isFragileTvOem()
                        )
                )

    fun sanitize(items: List<AppAdapter.Item>?): List<AppAdapter.Item> {
        if (items.isNullOrEmpty()) return emptyList()
        return items.mapNotNull { item ->
            when (item) {
                is Movie -> item.takeIf { it.id.isNotBlank() && it.title.isNotBlank() }
                is TvShow -> item.takeIf { it.id.isNotBlank() && it.title.isNotBlank() }
                is Genre -> item.takeIf { it.id.isNotBlank() && it.name.isNotBlank() }
                is People -> item.takeIf { it.id.isNotBlank() && it.name.isNotBlank() }
                is Category -> item.takeIf { it.name.isNotBlank() }
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
