package com.dskja.betterstreamflix.utils

import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import java.util.Calendar

enum class SearchSortMode {
    PROVIDER_DEFAULT,
    NEWEST_FIRST,
    ;

    companion object {
        fun fromKey(raw: String?): SearchSortMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: PROVIDER_DEFAULT
    }
}

object SearchSort {
    fun items(
        list: List<AppAdapter.Item>,
        mode: SearchSortMode = SearchSortMode.PROVIDER_DEFAULT,
        year: Int? = null,
    ): List<AppAdapter.Item> {
        val filtered = if (year == null) {
            list
        } else {
            list.filter { item ->
                when (item) {
                    is Movie, is TvShow -> releaseYear(item) == year
                    else -> true
                }
            }
        }

        return when (mode) {
            SearchSortMode.PROVIDER_DEFAULT -> filtered
            SearchSortMode.NEWEST_FIRST -> {
                val shows = mutableListOf<AppAdapter.Item>()
                val others = mutableListOf<AppAdapter.Item>()
                filtered.forEach { item ->
                    if (item is Movie || item is TvShow) shows += item else others += item
                }
                others + shows.sortedByDescending { releaseMillis(it) }
            }
        }
    }

    fun releaseYear(item: AppAdapter.Item): Int? = when (item) {
        is Movie -> item.released?.get(Calendar.YEAR)
        is TvShow -> item.released?.get(Calendar.YEAR)
        else -> null
    }

    fun releaseMillis(item: AppAdapter.Item): Long = when (item) {
        is Movie -> CatalogSort.releaseMillis(item.released)
        is TvShow -> CatalogSort.releaseMillis(item.released)
        else -> Long.MIN_VALUE
    }

    fun availableYears(list: List<AppAdapter.Item>): List<Int> =
        list.mapNotNull { releaseYear(it) }.distinct().sortedDescending()

    /** Years offered in the picker: result years (if any) plus a recent calendar window. */
    fun yearPickerOptions(
        list: List<AppAdapter.Item> = emptyList(),
        currentYear: Int = Calendar.getInstance().get(Calendar.YEAR),
        oldestYear: Int = 1950,
    ): List<Int> {
        val fromResults = availableYears(list)
        val window = (currentYear downTo oldestYear).toList()
        return (fromResults + window).distinct().sortedDescending()
    }
}
