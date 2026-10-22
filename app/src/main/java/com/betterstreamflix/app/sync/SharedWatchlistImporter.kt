package com.betterstreamflix.app.sync

import android.content.Context
import com.betterstreamflix.app.database.AppDatabase
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.providers.Provider
import com.betterstreamflix.app.ui.UserDataNotifier
import com.betterstreamflix.app.utils.ParentalControlUtils
import com.betterstreamflix.app.utils.UserPreferences

object SharedWatchlistImporter {
    data class Result(
        val imported: Int,
        val skippedUnknownProvider: Int,
        val alreadyPresent: Int,
        val skippedAgeRestricted: Int = 0,
    )

    suspend fun importPayload(context: Context, payload: SharedWatchlistPayload): Result {
        val appContext = context.applicationContext
        var imported = 0
        var skipped = 0
        var already = 0
        var skippedAge = 0
        val now = System.currentTimeMillis()

        for (item in payload.items) {
            val provider = Provider.findByName(item.provider)
            if (provider == null) {
                skipped++
                continue
            }
            val ownsCurrent = UserPreferences.currentProvider?.name == provider.name
            val db = if (ownsCurrent) {
                AppDatabase.getInstanceOrNull(appContext) ?: continue
            } else {
                AppDatabase.getInstanceForProviderOrNull(provider.name, appContext) ?: continue
            }
            try {
                when (item.mediaType) {
                    "movie" -> {
                        val existing = db.movieDao().getById(item.mediaId)
                        if (existing?.isFavorite == true) {
                            already++
                            continue
                        }
                        val movie = (existing ?: Movie(
                            id = item.mediaId,
                            title = item.title,
                            poster = item.poster,
                            banner = item.banner,
                        )).apply {
                            title = title.ifBlank { item.title }
                            poster = poster ?: item.poster
                            banner = banner ?: item.banner
                            providerName = provider.name
                            isFavorite = true
                            favoritedAtMillis = now
                        }
                        if (!ParentalControlUtils.isAllowed(movie)) {
                            skippedAge++
                            continue
                        }
                        FavoriteMutations.setMovieFavorite(
                            appContext,
                            provider,
                            movie,
                            favorite = true,
                            database = db,
                        )
                        imported++
                    }
                    "tv_show" -> {
                        val existing = db.tvShowDao().getById(item.mediaId)
                        if (existing?.isFavorite == true) {
                            already++
                            continue
                        }
                        val show = (existing ?: TvShow(
                            id = item.mediaId,
                            title = item.title,
                            poster = item.poster,
                            banner = item.banner,
                        )).apply {
                            title = title.ifBlank { item.title }
                            poster = poster ?: item.poster
                            banner = banner ?: item.banner
                            providerName = provider.name
                            isFavorite = true
                            favoritedAtMillis = now
                        }
                        if (!ParentalControlUtils.isAllowed(show)) {
                            skippedAge++
                            continue
                        }
                        FavoriteMutations.setTvShowFavorite(
                            appContext,
                            provider,
                            show,
                            favorite = true,
                            database = db,
                        )
                        imported++
                    }
                    else -> skipped++
                }
            } finally {
                if (!ownsCurrent) runCatching { db.close() }
            }
        }
        if (imported > 0) {
            UserDataNotifier.notifyChanged()
            CloudSyncScheduler.enqueue(appContext)
        }
        return Result(imported, skipped, already, skippedAge)
    }
}
