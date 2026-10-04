package com.dskja.betterstreamflix.fragments.search

import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Genre
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow

/**
 * TV Search grid plans. Global mode must not submit empty or loading
 * [AppAdapter.Type.CATEGORY_TV_ITEM] rows: those shelves hide their
 * HorizontalGridView and Leanback then crashes on DPAD / layout.
 */
object SearchTvRows {

    const val MAX_LOCAL = SearchResultGuard.MAX_LOCAL_RESULTS
    const val MAX_GLOBAL_PER_PROVIDER = SearchResultGuard.MAX_GLOBAL_PER_PROVIDER
    const val GENRE_COLUMNS = 5
    const val RESULT_COLUMNS = 6
    const val GLOBAL_COLUMNS = 1

    data class Grid(
        val columns: Int,
        val items: List<AppAdapter.Item>,
    ) {
        val hasFocusableContent: Boolean get() = items.isNotEmpty()
    }

    fun local(query: String, items: List<AppAdapter.Item>): Grid {
        val safe = SearchResultGuard.sanitize(items).take(MAX_LOCAL)
        safe.forEach { item ->
            when (item) {
                is Genre -> item.itemType = AppAdapter.Type.GENRE_GRID_TV_ITEM
                is Movie -> item.itemType = AppAdapter.Type.MOVIE_GRID_TV_ITEM
                is TvShow -> item.itemType = AppAdapter.Type.TV_SHOW_GRID_TV_ITEM
            }
        }
        val columns = if (query.isBlank()) GENRE_COLUMNS else RESULT_COLUMNS
        return Grid(columns = columns, items = safe)
    }

    fun global(
        providerResults: List<ProviderResult>,
        titleFor: (providerName: String, count: Int) -> String,
    ): Grid = globalShelves(
        rows = providerResults.map { it.provider.name to it.state },
        titleFor = titleFor,
    )

    internal fun globalShelves(
        rows: List<Pair<String, ProviderResult.State>>,
        titleFor: (providerName: String, count: Int) -> String,
    ): Grid {
        val categories = rows.mapNotNull { (providerName, state) ->
            val success = state as? ProviderResult.State.Success ?: return@mapNotNull null
            val hits = SearchResultGuard.sanitize(success.results)
                .take(MAX_GLOBAL_PER_PROVIDER)
            if (hits.isEmpty()) return@mapNotNull null
            hits.forEach { item ->
                when (item) {
                    is Movie -> item.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                    is TvShow -> item.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                }
            }
            Category(
                name = titleFor(providerName, hits.size),
                list = hits,
            ).apply {
                identityKey = "search-global:$providerName"
                itemType = AppAdapter.Type.CATEGORY_TV_ITEM
            }
        }
        return Grid(columns = GLOBAL_COLUMNS, items = categories)
    }

    /**
     * Where DPAD Down from the sort chips should land.
     * An empty or invisible grid is not a safe Leanback target.
     */
    fun chipDownId(
        hasGridItems: Boolean,
        emptyCtaVisible: Boolean,
        gridId: Int,
        emptyCtaId: Int,
        fallbackId: Int,
    ): Int = when {
        hasGridItems -> gridId
        emptyCtaVisible -> emptyCtaId
        else -> fallbackId
    }
}
