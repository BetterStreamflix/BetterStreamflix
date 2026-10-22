package com.betterstreamflix.app.home

import android.content.Context
import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.download.DownloadRepository
import com.betterstreamflix.app.download.OfflineLibrary
import com.betterstreamflix.app.models.Category
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.utils.DeviceCapabilities
import java.util.concurrent.atomic.AtomicReference

/**
 * Downloads companion shelf for Home — pinnable via [HomeShelfLayout], sits in
 * the post-Remix “rest” region unless pinned under Featured.
 */
object HomeShelfCompanions {

    private const val DOWNLOADS_SHELF_LIMIT = 20

    private data class CacheEntry(
        val mobile: Boolean,
        val fingerprint: String,
        val category: Category?,
    )

    private val cache = AtomicReference<CacheEntry?>(null)

    fun invalidateDownloadsCache() {
        cache.set(null)
    }

    suspend fun downloadsShelf(
        context: Context,
        mobile: Boolean = !DeviceCapabilities.isLeanbackDevice(context),
    ): Category? {
        val completed = runCatching {
            DownloadRepository.get(context).getAllOnce()
        }.getOrElse { emptyList() }
        val fingerprint = completed.joinToString("|") { "${it.id}:${it.state}:${it.updatedAt}" }
            .ifEmpty { "empty" }
        cache.get()?.let { hit ->
            if (hit.mobile == mobile && hit.fingerprint == fingerprint) {
                return hit.category
            }
        }
        val entries = OfflineLibrary.build(completed).take(DOWNLOADS_SHELF_LIMIT)
        val category = if (entries.isEmpty()) {
            null
        } else {
            val items = OfflineLibrary.toAppItems(entries, mobile = mobile).map { item ->
                when (item) {
                    is Movie -> item.also {
                        it.itemType = if (mobile) {
                            AppAdapter.Type.MOVIE_MOBILE_ITEM
                        } else {
                            AppAdapter.Type.MOVIE_TV_ITEM
                        }
                    }
                    is TvShow -> item.also {
                        it.itemType = if (mobile) {
                            AppAdapter.Type.TV_SHOW_MOBILE_ITEM
                        } else {
                            AppAdapter.Type.TV_SHOW_TV_ITEM
                        }
                    }
                    else -> item
                }
            }
            Category(name = Category.DOWNLOADS, list = items).also {
                it.identityKey = Category.DOWNLOADS_ID
            }
        }
        cache.set(CacheEntry(mobile = mobile, fingerprint = fingerprint, category = category))
        return category
    }
}
