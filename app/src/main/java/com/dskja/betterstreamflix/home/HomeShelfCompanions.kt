package com.dskja.betterstreamflix.home

import android.content.Context
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.download.DownloadRepository
import com.dskja.betterstreamflix.download.OfflineLibrary
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow

/**
 * Downloads companion shelf for Home — pinnable via [HomeShelfLayout], sits in
 * the post-Remix “rest” region unless pinned under Featured.
 */
object HomeShelfCompanions {

    private const val DOWNLOADS_SHELF_LIMIT = 20

    suspend fun downloadsShelf(context: Context): Category? {
        val completed = runCatching {
            DownloadRepository.get(context).getAllOnce()
        }.getOrElse { emptyList() }
        val entries = OfflineLibrary.build(completed).take(DOWNLOADS_SHELF_LIMIT)
        if (entries.isEmpty()) return null
        val items = OfflineLibrary.toAppItems(entries, mobile = true).map { item ->
            when (item) {
                is Movie -> item.also { it.itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM }
                is TvShow -> item.also { it.itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM }
                else -> item
            }
        }
        return Category(name = Category.DOWNLOADS, list = items).also {
            it.identityKey = Category.DOWNLOADS_ID
        }
    }
}
