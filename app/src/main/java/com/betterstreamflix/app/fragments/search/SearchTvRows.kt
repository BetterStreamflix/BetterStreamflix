package com.betterstreamflix.app.fragments.search

import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.models.Category
import com.betterstreamflix.app.models.Genre
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.People
import com.betterstreamflix.app.models.TvShow

/**
 * TV Search grid plans. Global mode must not submit empty or loading
 * [AppAdapter.Type.CATEGORY_TV_ITEM] rows: those shelves hide their
 * HorizontalGridView and Leanback then crashes on DPAD / layout.
 */
object SearchTvRows {

    val MAX_LOCAL: Int get() = SearchResultGuard.maxLocalResults()
    val MAX_GLOBAL_PER_PROVIDER: Int get() = SearchResultGuard.maxGlobalPerProvider()
    /** Delegates to [com.betterstreamflix.app.ui.LeanbackDensityPolicy] (Compact/Comfort). */
    val GENRE_COLUMNS: Int
        get() = com.betterstreamflix.app.ui.LeanbackDensityPolicy.searchGenreColumns()
    val RESULT_COLUMNS: Int
        get() = com.betterstreamflix.app.ui.LeanbackDensityPolicy.searchResultColumns()
    const val GLOBAL_COLUMNS = 1

    data class Grid(
        val columns: Int,
        val items: List<AppAdapter.Item>,
    ) {
        val hasFocusableContent: Boolean get() = items.isNotEmpty()
    }

    fun local(query: String, items: List<AppAdapter.Item>): Grid {
        val capped = SearchResultGuard.sanitize(items).take(MAX_LOCAL)
        // Blank Search shows genre chips — hide mature genres under Kids.
        // Drop empty Category headers — CATEGORY_TV_ITEM with no children traps Leanback.
        val filtered = capped.filterNot { it is Category && it.list.isEmpty() }
        val safe = if (query.isBlank()) {
            com.betterstreamflix.app.utils.ParentalControlUtils.filterGenreChips(filtered)
        } else {
            filtered
        }
        safe.forEach { item ->
            when (item) {
                is Genre -> item.itemType = AppAdapter.Type.GENRE_GRID_TV_ITEM
                is Movie -> item.itemType = AppAdapter.Type.MOVIE_GRID_TV_ITEM
                is TvShow -> item.itemType = AppAdapter.Type.TV_SHOW_GRID_TV_ITEM
                is People -> item.itemType = AppAdapter.Type.PEOPLE_TV_ITEM
                is Category -> item.itemType = AppAdapter.Type.CATEGORY_TV_ITEM
            }
        }
        val columns = if (query.isBlank()) {
            com.betterstreamflix.app.ui.LeanbackDensityPolicy.searchGenreColumns()
        } else {
            com.betterstreamflix.app.ui.LeanbackDensityPolicy.searchResultColumns()
        }
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
            // Never submit empty CATEGORY_TV_ITEM shelves (Leanback focus trap).
            if (hits.isEmpty()) return@mapNotNull null
            hits.forEach { item ->
                when (item) {
                    is Movie -> item.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                    is TvShow -> item.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                    is People -> item.itemType = AppAdapter.Type.PEOPLE_TV_ITEM
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
