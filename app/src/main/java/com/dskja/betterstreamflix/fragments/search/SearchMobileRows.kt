package com.dskja.betterstreamflix.fragments.search

import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Genre
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.People
import com.dskja.betterstreamflix.models.TvShow

/**
 * Mobile Search list plans. Global mode keeps a full-width provider header
 * plus grid posters, and skips providers that are still loading, failed, or empty.
 */
object SearchMobileRows {

    const val MAX_LOCAL = SearchResultGuard.MAX_LOCAL_RESULTS
    const val MAX_GLOBAL_PER_PROVIDER = SearchResultGuard.MAX_GLOBAL_PER_PROVIDER
    const val GRID_SPAN = 3

    data class Global(
        val items: List<AppAdapter.Item>,
        val stillLoading: Boolean,
    ) {
        val hasHits: Boolean get() = items.any { it !is Category }
    }

    fun local(items: List<AppAdapter.Item>?): List<AppAdapter.Item> {
        val safe = SearchResultGuard.bound(items, MAX_LOCAL)
        // Drop orphan Category headers with no following show/people rows (mood/negatives).
        val cleaned = dropOrphanCategoryHeaders(safe)
        cleaned.forEach { stampGrid(it) }
        return cleaned
    }

    private fun dropOrphanCategoryHeaders(items: List<AppAdapter.Item>): List<AppAdapter.Item> {
        if (items.none { it is Category }) return items
        return buildList {
            var i = 0
            while (i < items.size) {
                val item = items[i]
                if (item is Category) {
                    var j = i + 1
                    var hasChild = false
                    while (j < items.size && items[j] !is Category) {
                        if (items[j] is Movie || items[j] is TvShow || items[j] is People) {
                            hasChild = true
                            break
                        }
                        j++
                    }
                    if (hasChild) add(item)
                } else {
                    add(item)
                }
                i++
            }
        }
    }

    fun global(
        providerResults: List<ProviderResult>,
        titleFor: (providerName: String, count: Int) -> String,
    ): Global = globalSections(
        rows = providerResults.map { it.provider.name to it.state },
        titleFor = titleFor,
        stillLoading = providerResults.any { it.state is ProviderResult.State.Loading },
    )

    internal fun globalSections(
        rows: List<Pair<String, ProviderResult.State>>,
        titleFor: (providerName: String, count: Int) -> String,
        stillLoading: Boolean,
    ): Global {
        val items = mutableListOf<AppAdapter.Item>()
        rows.forEach { (providerName, state) ->
            val success = state as? ProviderResult.State.Success ?: return@forEach
            val hits = SearchResultGuard.bound(success.results, MAX_GLOBAL_PER_PROVIDER)
            if (hits.isEmpty()) return@forEach
            hits.forEach { stampGrid(it) }
            items += Category(
                name = titleFor(providerName, hits.size),
                list = emptyList(),
            ).apply {
                identityKey = "search-global:$providerName"
                itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM
            }
            items += hits
        }
        return Global(items = items, stillLoading = stillLoading)
    }

    fun spanSize(viewType: Int, spanCount: Int = GRID_SPAN): Int {
        val count = spanCount.coerceAtLeast(1)
        val fullWidth = viewType == AppAdapter.Type.CATEGORY_MOBILE_ITEM.ordinal ||
            viewType == AppAdapter.Type.HEADER.ordinal ||
            viewType == AppAdapter.Type.FOOTER.ordinal ||
            viewType == AppAdapter.Type.LOADING_ITEM.ordinal
        return if (fullWidth) count else 1
    }

    private fun stampGrid(item: AppAdapter.Item) {
        when (item) {
            is Genre -> item.itemType = AppAdapter.Type.GENRE_GRID_MOBILE_ITEM
            is Movie -> item.itemType = AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM
            is TvShow -> item.itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
            is People -> item.itemType = AppAdapter.Type.PEOPLE_MOBILE_ITEM
            is Category -> item.itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM
        }
    }
}
